package org.fastcatsearch.transport;



import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.io.FileUtils;
import org.fastcatsearch.cluster.Node;
import org.fastcatsearch.common.BytesReference;
import org.fastcatsearch.common.io.BytesStreamOutput;
import org.fastcatsearch.common.io.CachedStreamOutput;
import org.fastcatsearch.common.io.Streamable;
import org.fastcatsearch.ir.config.IRConfig;
import org.fastcatsearch.ir.config.IRSettings;
import org.fastcatsearch.job.StreamableJob;
import org.fastcatsearch.service.CatServiceComponent;
import org.fastcatsearch.transport.common.ByteCounter;
import org.fastcatsearch.transport.common.FileChannelHandler;
import org.fastcatsearch.transport.common.FileTransportHandler;
import org.fastcatsearch.transport.common.MessageChannelHandler;
import org.fastcatsearch.transport.common.MessageCounter;
import org.fastcatsearch.transport.common.ReadableFrameDecoder;
import org.fastcatsearch.transport.common.ResultFuture;
import org.fastcatsearch.transport.common.SendFileResultFuture;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.util.HashedWheelTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransportService extends CatServiceComponent {
	private static Logger logger = LoggerFactory.getLogger(TransportService.class);
	
	public static final int OPTION_COMPRESS_CHANNEL = 1 >> 1;
	
	private Map<Long, ResultFuture> resultFutureMap;

    private final AtomicLong requestIds = new AtomicLong();
	    
	private volatile ClientBootstrap clientBootstrap;

    private volatile ServerBootstrap serverBootstrap;
    
	private ConcurrentMap<Node, NodeChannels> connectedNodes;
	
	private volatile Channel serverChannel;
	
	private final Object[] connectMutex;
	private ExecutorService executorService;
	final int workerCount;
    final int bossCount;

    final int port;

    final boolean tcpNoDelay;

    final boolean tcpKeepAlive;

    final boolean reuseAddress;
    final int connectTimeout;

    final int tcpSendBufferSize;
    final int tcpReceiveBufferSize;
    
    final int sendFileChunkSize;
    
    private final ReadWriteLock globalLock = new ReentrantReadWriteLock();
    private FileTransportHandler fileTransportHandler;
    private static TransportService instance;
    
    public static TransportService getInstance(){
    	if(instance == null){
    		instance = new TransportService(IRSettings.getConfig());
    	}
    	return instance;
    }
	protected TransportService(IRConfig config){
		
		this.connectMutex = new Object[500];
        for (int i = 0; i < connectMutex.length; i++) {
            connectMutex[i] = new Object();
        }
        this.workerCount = config.getInt("worker_count", Runtime.getRuntime().availableProcessors() * 2);
        this.port = config.getInt("node_port");
        this.connectTimeout = config.getInt("connect_timeout", 1000);
        this.bossCount = config.getInt("boss_count", 1);
        this.tcpNoDelay = config.getBoolean("tcp_no_delay", true);
        this.tcpKeepAlive = config.getBoolean("tcp_keep_alive", true);
        this.reuseAddress = config.getBoolean("reuse_address", true);
        this.tcpSendBufferSize = config.getInt("tcp_send_buffer_size", 8192);
        this.tcpReceiveBufferSize = config.getInt("tcp_receive_buffer_size", 8192);
        this.sendFileChunkSize = config.getInt("send_file_chunk_size", 3 * 1024 * 1024);
        
        logger.debug("Transport setting worker_count[{}], port[{}], connect_timeout[{}]",
                new Object[]{workerCount, port, connectTimeout});
	}
	
	public boolean start0(){
		
		this.executorService = Executors.newCachedThreadPool();
		/*
		 * Client
		 * */
		clientBootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(),
                bossCount,
                new NioWorkerPool(Executors.newCachedThreadPool(), workerCount),
                new HashedWheelTimer()
                ));
		
		clientBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			public ChannelPipeline getPipeline() throws Exception {
				ChannelHandler readableDecoder = new ReadableFrameDecoder();
				ByteCounter byteCounter = new ByteCounter("ClientByteCounter");
				MessageCounter messageCounter = new MessageCounter("ClientMessageCounter");
				return Channels.pipeline(byteCounter, 
						readableDecoder,
						messageCounter, 
						new MessageChannelHandler(TransportService.this));
			}
		});
		clientBootstrap.setOption("connectTimeoutMillis", connectTimeout);
        clientBootstrap.setOption("tcpNoDelay", tcpNoDelay);
        clientBootstrap.setOption("keepAlive", tcpKeepAlive);
        if (tcpSendBufferSize > 0) {
            clientBootstrap.setOption("sendBufferSize", tcpSendBufferSize);
        }
        if (tcpReceiveBufferSize > 0) {
            clientBootstrap.setOption("receiveBufferSize", tcpReceiveBufferSize);
        }
        clientBootstrap.setOption("reuseAddress", reuseAddress);
        
        /*
		 * Server
		 * */
        
        fileTransportHandler  = new FileTransportHandler(); 
        serverBootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool(),
                workerCount));
        
		serverBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			public ChannelPipeline getPipeline() throws Exception {
				ChannelHandler readableDecoder = new ReadableFrameDecoder();
				ByteCounter byteCounter = new ByteCounter("ServerByteCounter");
				MessageCounter messageCounter = new MessageCounter("ServerMessageCounter");
				return Channels.pipeline(byteCounter, 
						readableDecoder,
						messageCounter,
						new MessageChannelHandler(TransportService.this),
						new FileChannelHandler(TransportService.this, fileTransportHandler)
						);
			}
		});
        serverBootstrap.setOption("child.tcpNoDelay", tcpNoDelay);
        serverBootstrap.setOption("child.keepAlive", tcpKeepAlive);
        if (tcpSendBufferSize > 0) {
            serverBootstrap.setOption("child.sendBufferSize", tcpSendBufferSize);
        }
        if (tcpReceiveBufferSize > 0) {
            serverBootstrap.setOption("child.receiveBufferSize", tcpReceiveBufferSize);
        }
        serverBootstrap.setOption("reuseAddress", reuseAddress);
        serverBootstrap.setOption("child.reuseAddress", reuseAddress);
        
        
        serverChannel = serverBootstrap.bind(new InetSocketAddress(port));
        logger.debug("Bound to address [{}]", serverChannel.getLocalAddress());
        
        connectedNodes = new ConcurrentHashMap<Node, NodeChannels>();
        resultFutureMap = new ConcurrentHashMap<Long, ResultFuture>();
        
        return true;
	}
	
	protected boolean shutdown0() {
        final CountDownLatch latch = new CountDownLatch(1);
        // make sure we run it on another thread than a possible IO handler thread
        execute(new Runnable() {
            @Override
            public void run() {
                globalLock.writeLock().lock();
                try {
                    for (Iterator<NodeChannels> it = connectedNodes.values().iterator(); it.hasNext(); ) {
                        NodeChannels nodeChannels = it.next();
                        it.remove();
                        nodeChannels.close();
                    }

                    if (serverChannel != null) {
                        try {
                            serverChannel.close().awaitUninterruptibly();
                        } finally {
                            serverChannel = null;
                        }
                    }

                    if (serverBootstrap != null) {
                        serverBootstrap.releaseExternalResources();
                        serverBootstrap = null;
                    }

                    if (clientBootstrap != null) {
                        clientBootstrap.releaseExternalResources();
                        clientBootstrap = null;
                    }
                } finally {
                    globalLock.writeLock().unlock();
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
        
        return true;
    }
	
	private Object connectLock(String nodeId) {
        int hash = nodeId.hashCode();
        // abs returns Integer.MIN_VALUE, so we need to protect against it...
        if (hash == Integer.MIN_VALUE) {
            hash = 0;
        }
        return connectMutex[Math.abs(hash) % connectMutex.length];
    }
	
	public void connectToNode(Node node) throws TransportException{
		globalLock.readLock().lock();
		
		try{
		
			synchronized (connectLock(node.id())) {
	           
	            try {
	                NodeChannels nodeChannels = connectedNodes.get(node);
	                if (nodeChannels != null) {
	                    return;
	                }
	
	                try {
	                    InetSocketAddress address = node.address();
	                    
	                    ChannelFuture connectLow = clientBootstrap.connect(address);
	                    
	                    ChannelFuture connectHigh = clientBootstrap.connect(address);
	                    
	                    nodeChannels = new NodeChannels();
	               
	                
		                try{
		                    connectLow.awaitUninterruptibly((long) (connectTimeout * 1.5));
		                    if (!connectLow.isSuccess()) {
		                        throw new TransportException(node, "connect_timeout[" + connectTimeout + "]", connectLow.getCause());
		                    }
		                    nodeChannels.setLowChannel(connectLow.getChannel());
		                    nodeChannels.getLowChannel().getCloseFuture().addListener(new ChannelCloseListener(node));
		                    
		                    
		                    connectHigh.awaitUninterruptibly((long) (connectTimeout * 1.5));
		                    if (!connectHigh.isSuccess()) {
		                        throw new TransportException(node, "connect_timeout[" + connectTimeout + "]", connectHigh.getCause());
		                    }
		                    nodeChannels.setHighChannel(connectHigh.getChannel());
		                    nodeChannels.getHighChannel().getCloseFuture().addListener(new ChannelCloseListener(node));
		                } catch (RuntimeException e) {
		                    // clean the futures
		                    	connectLow.cancel();
		                    	connectHigh.cancel();
		                        if (connectLow.getChannel() != null && connectLow.getChannel().isOpen()) {
		                            try {
		                            	connectLow.getChannel().close();
		                            } catch (Exception e1) {
		                                // ignore
		                            }
		                        }
		                        if (connectHigh.getChannel() != null && connectHigh.getChannel().isOpen()) {
		                            try {
		                            	connectHigh.getChannel().close();
		                            } catch (Exception e1) {
		                                // ignore
		                            }
		                        }
		                    throw e;
		                }
	                    
	                    
	                } catch (Exception e) {
	                    nodeChannels.close();
	                    throw e;
	                }
	
	                NodeChannels existing = connectedNodes.putIfAbsent(node, nodeChannels);
	                if (existing != null) {
	                    // we are already connected to a node, close this ones
	                    nodeChannels.close();
	                } else {
	                    if (logger.isDebugEnabled()) {
	                        logger.debug("connected to node [{}]", node);
	                    }
	//                    transportServiceAdapter.raiseNodeConnected(node);
	                }
	
	            } catch (TransportException e) {
	                throw e;
	            } catch (Exception e) {
	                throw new TransportException(node, "General node connection failure", e);
	            }
	        }
		}finally{
			globalLock.readLock().unlock();
		}
	}
	
	private NodeChannels getNodeChannels(Node node) throws TransportException {
		NodeChannels channels = connectedNodes.get(node);
		if(channels == null){
			//TODO 연결시도.
			throw new TransportException(node, "연결할수 없습니다.");
		}
		
		return channels;
	}

    public ResultFuture sendRequest(final Node node, final StreamableJob streamableJob) throws TransportException {
        final long requestId = newRequestId();
        try {
        	ResultFuture resultFuture = new ResultFuture(requestId, resultFutureMap, System.currentTimeMillis());
            resultFutureMap.put(requestId, resultFuture);
            sendMessageRequest(node, requestId, streamableJob);
            
            return resultFuture;
        } catch (final Exception e) {
            resultFutureMap.remove(requestId);
           
           throw new TransportException("메시지 전송중 에러발생.", e);
        }
    }
    
    public SendFileResultFuture sendFile(final Node node, final File file) throws TransportException {
    	final long requestId = newRequestId();
    	try {
    		SendFileResultFuture resultFuture = new SendFileResultFuture(requestId, resultFutureMap, System.currentTimeMillis());
            resultFutureMap.put(requestId, resultFuture);
            sendFileRequest(node, requestId, file, resultFuture);
            
            return resultFuture;
        } catch (final Exception e) {
            resultFutureMap.remove(requestId);
           
           throw new TransportException("메시지 전송중 에러발생.", e);
        }
    }
    
    public void resultReceived(long requestId, Streamable streamableResult) {
    	ResultFuture resultFuture = resultFutureMap.remove(requestId);
    	if(resultFuture == null){
    		//입력할 결과객체가 없음.
    		logger.warn("입력할 결과객체가 없음. timeout으로 제거되었을수있습니다. requestId={}, streamableResult={}", requestId, streamableResult);
    	}else{
    		resultFuture.put(streamableResult, true);
    	}
	}

	public void exceptionReceived(long requestId, Throwable e) {
		ResultFuture resultFuture = resultFutureMap.remove(requestId);
    	if(resultFuture == null){
    		//입력할 결과객체가 없음.
    		logger.warn("입력할 결과객체가 없음. timeout으로 제거되었을수있습니다. requestId={}, Throwable={}", requestId, e);
    	}else{
    		resultFuture.put(e, false);
    	}
		
	}
	
	
    private long newRequestId() {
        return requestIds.getAndIncrement();
    }
    
    private void sendMessageRequest(final Node node, long requestId, StreamableJob request) throws IOException, TransportException {
		NodeChannels channels = getNodeChannels(node);
		Channel targetChannel = channels.getHighChannel();
		byte type = 0;
        byte status = 0;
        status = TransportOption.setRequest(status);
        CachedStreamOutput.Entry cachedEntry = CachedStreamOutput.popEntry();
        BytesStreamOutput stream = cachedEntry.bytes();
        stream.skip(MessageProtocol.HEADER_SIZE);
        stream.writeString(request.getClass().getName());
        logger.debug("write class {}", request.getClass().getName());
        request.writeTo(stream);
        stream.close();
        
        
        ChannelBuffer buffer = stream.bytes().toChannelBuffer();
        MessageProtocol.writeHeader(buffer, type, requestId, status);

        ChannelFuture future = targetChannel.write(buffer);
        future.addListener(new CacheFutureListener(cachedEntry));
    }
	
    private String getHashedFilePath(String filePath){
        UUID uuid =  UUID.nameUUIDFromBytes(filePath.getBytes());
        return Long.toHexString(uuid.getMostSignificantBits()) + Long.toHexString(uuid.getLeastSignificantBits());
    }
    
    /*
     * header + seq(4) + [filepath(string) + filesize(long) + checksumCRC32(long)]+ hashfilepath(string) + datalength(vint) + data 
     * */
	private void sendFileRequest(final Node node, final long requestId, File file, SendFileResultFuture resultFuture) throws IOException, TransportException {
		NodeChannels channels = getNodeChannels(node);
		Channel targetChannel = channels.getLowChannel();
		byte type = 0;
		type = TransportOption.setFile(type);
		byte status = 0;
        FileChunkEnumeration enumeration = null;
        try{
        	enumeration = new FileChunkEnumeration(file, sendFileChunkSize);
	    	String filePath = file.getAbsolutePath();
	    	long checksumCRC32 = FileUtils.checksumCRC32(file);
	        long fileSize = file.length();
	        long writeSize = 0;
	        String hashedFilePath = getHashedFilePath(filePath);
	    	for(int seq = 0; enumeration.hasMoreElements(); seq++){
	    		if(resultFuture.isCanceled()){
	    			logger.info("파일전송이 중단되었습니다. file={}", file.getAbsolutePath());
	    			break;
	    		}
	    		
	    		BytesReference bytesRef = enumeration.nextElement();
	    		writeSize += bytesRef.length();
	    		
	    		CachedStreamOutput.Entry cachedEntry = CachedStreamOutput.popEntry();
	            BytesStreamOutput stream = cachedEntry.bytes();
	            stream.skip(MessageProtocol.HEADER_SIZE);
	    		
	            //write seq ( 0,1,2,3,4....)
	            stream.writeInt(seq);
	            
	            if(seq == 0){
	            	//시작시에는 파일명과 총파일크기를 보낸다.
	                //write file path
	                stream.writeString(filePath);
	                //write file size
	                stream.writeLong(fileSize);
	                stream.writeLong(checksumCRC32);
	            }
	            
	            stream.writeString(hashedFilePath);
	            
	            //write file data
	            stream.writeVInt(bytesRef.length());
	            stream.write(bytesRef.array());
	            
	            stream.close();
	            //TODO 만약 이 라인 이전에 에러발생시 cache가 리턴되지 않고 누락되는 잠재버그가 발생할수있다.
	            
	            ChannelBuffer buffer = stream.bytes().toChannelBuffer();
	            MessageProtocol.writeHeader(buffer, type, requestId, status);
	            
	            ChannelFuture future = targetChannel.write(buffer);
	            future.addListener(new CacheFutureListener(cachedEntry));
	    	}
        
	    	assert fileSize != writeSize: "파일사이즈가 다릅니다.";
	    	if(fileSize != writeSize){
	    		logger.error("파일사이즈가 다릅니다. expected={}, actual={}, file={}", new Object[]{fileSize, writeSize, filePath});
	    	}else{
	    		logger.error("File Write Success filesize={}, file={}", writeSize, filePath);
	    	}
        }finally{
        	enumeration.close();
        }
	}
	
	public void disconnectFromNode(Node node) {
		synchronized (connectLock(node.id())) {
			NodeChannels nodeChannels = connectedNodes.remove(node);
			if (nodeChannels != null) {
				try {
					nodeChannels.close();
				} finally {
					logger.debug("disconnected from [{}]", node);
				}
			}
		}
	}
	
	
	private class ChannelCloseListener implements ChannelFutureListener {

        private final Node node;

        private ChannelCloseListener(Node node) {
            this.node = node;
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            disconnectFromNode(node);
        }
    }
	
	public static class CacheFutureListener implements ChannelFutureListener {

        private final CachedStreamOutput.Entry cachedEntry;

        public CacheFutureListener(CachedStreamOutput.Entry cachedEntry) {
            this.cachedEntry = cachedEntry;
        }

        @Override
        public void operationComplete(ChannelFuture channelFuture) throws Exception {
            CachedStreamOutput.pushEntry(cachedEntry);
        }
    }

	public void execute(Runnable requestRunnable) {
		executorService.execute(requestRunnable);
	}

	
}
