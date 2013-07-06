package org.fastcatsearch.ir.io;

import java.io.File;
import java.io.IOException;

import org.apache.lucene.util.BytesRef;
import org.fastcatsearch.ir.common.IRFileName;

public class FixedDataInput implements SequencialDataInput {
	private Input dataInput;
	private int dataSize;
	
	public FixedDataInput(File dir, String fileName, int dataSize) throws IOException{
		File dataFile = new File(dir, IRFileName.getSuffixFileName(fileName, "data"));
		dataInput = new BufferedFileInput(dataFile);
		this.dataSize = dataSize;
	}
	
	//범위체크하지 않음.
	@Override
	public boolean read(BytesRef bytesRef, long sequence) throws IOException{
		dataInput.position(dataSize * sequence);
		int size = dataInput.readVariableByte();
		if(bytesRef == null || bytesRef.bytes.length < size){
			bytesRef.bytes = new byte[size];
		}
		dataInput.readBytes(bytesRef.bytes, 0, size);
		bytesRef.offset = 0;
		bytesRef.length = size;
		return true;
	}
	
	@Override
	public void close() throws IOException{
		dataInput.close();
	}
}