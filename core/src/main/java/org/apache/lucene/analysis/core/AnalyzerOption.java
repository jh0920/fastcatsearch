package org.apache.lucene.analysis.core;

public class AnalyzerOption {
	private boolean useStopword;
	private boolean useSynonym;
	
	
	public boolean useStopword(){
		return useStopword;
	}
	
	public void useStopword(boolean useStopword){
		this.useStopword = useStopword;
	}
	
	public boolean useSynonym(){
		return useSynonym;
	}
	
	public void useSynonym(boolean useSynonym){
		this.useSynonym = useSynonym;
	}
}
