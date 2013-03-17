/*
 * Copyright (c) 2013 Websquared, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     swsong - initial API and implementation
 */

package org.fastcatsearch.job;

import java.io.IOException;

import org.fastcatsearch.common.io.StreamInput;
import org.fastcatsearch.common.io.StreamOutput;


public class TestJob extends StreamableJob{

	public TestJob(){ }
	
	public TestJob(String str){
		args = new String[]{str};
	}
	
	@Override
	public Object run0() {
		String[] args = getStringArrayArgs();
		String str = args[0];

		logger.debug("This is Test Job!! args="+str);
		
		return str;
	}

	@Override
	public void readFrom(StreamInput input) throws IOException {
		
	}

	@Override
	public void writeTo(StreamOutput output) throws IOException {
		
	}

}
