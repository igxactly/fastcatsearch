package org.fastcatsearch.servlet;

import java.io.StringWriter;

import org.fastcatsearch.ir.group.GroupEntry;
import org.fastcatsearch.ir.group.GroupKey.DateTimeKey;
import org.fastcatsearch.ir.group.GroupKey.IntKey;
import org.fastcatsearch.ir.group.GroupKey.StringKey;
import org.fastcatsearch.ir.group.GroupResult;
import org.fastcatsearch.ir.group.GroupResults;
import org.fastcatsearch.ir.query.Group;
import org.fastcatsearch.ir.query.Groups;
import org.fastcatsearch.util.JSONResultStringer;
import org.fastcatsearch.util.ResultStringer;
import org.junit.Test;

public class GroupResultWriterTest {

	@Test
	public void test() {
		StringWriter writer = new StringWriter();
		GroupResultWriter groupResultWriter = new GroupResultWriter(writer);
		
		int groupSize = 3;
		int totalSearchCount = 11200;
		
		GroupResults result =  new GroupResults(groupSize, totalSearchCount);
		GroupResult groupResult1 = new GroupResult(Group.DEFAULT_AGGREGATION_FUNCTION_NAME, 10000, 3);
		groupResult1.setEntry(0, new GroupEntry(new IntKey(1), 800));
		groupResult1.setEntry(1, new GroupEntry(new IntKey(2), 150));
		groupResult1.setEntry(2, new GroupEntry(new IntKey(3), 50));
		result.add(groupResult1);
		GroupResult groupResult2 = new GroupResult(Group.DEFAULT_AGGREGATION_FUNCTION_NAME, 1000, 3);
		groupResult2.setEntry(0, new GroupEntry(new StringKey("Korea".toCharArray()), 650));
		groupResult2.setEntry(1, new GroupEntry(new StringKey("Japan".toCharArray()), 250));
		groupResult2.setEntry(2, new GroupEntry(new StringKey("America".toCharArray()), 100));
		result.add(groupResult2);
		GroupResult groupResult3 = new GroupResult(Group.DEFAULT_AGGREGATION_FUNCTION_NAME, 200, 3);
		groupResult3.setEntry(0, new GroupEntry(new DateTimeKey(System.currentTimeMillis()), 100));
		groupResult3.setEntry(1, new GroupEntry(new DateTimeKey(System.currentTimeMillis()), 70));
		groupResult3.setEntry(2, new GroupEntry(new DateTimeKey(System.currentTimeMillis()), 30));
		result.add(groupResult3);
		
		
		ResultStringer stringer = new JSONResultStringer();
		long searchTime = 1234;
		boolean isSuccess = true;
		groupResultWriter.writeResult(result, stringer, searchTime, isSuccess);
		
		
		System.out.println(writer.toString());
	}

}
