/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package indexer;

import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Acts as an utility class for query construction and retrieval from a Lucene index.
 * 
 * @author dganguly
 */
public class PassageRetriever {
    Properties prop;
    IndexReader reader;
    IndexSearcher searcher;
    Analyzer analyzer;
 
    /**
     * Constructs this object from a given properties file.
     * 
     * @param propFile Path to properties file.
     */
    public PassageRetriever(String propFile) throws Exception {
        prop = new Properties();
        prop.load(new FileReader(propFile));        
        
        String indexPath = prop.getProperty("para.index.all");
        File indexDir = new File(indexPath);
        reader = DirectoryReader.open(FSDirectory.open(indexDir.toPath()));
        searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new LMJelinekMercerSimilarity(0.6f));

        analyzer = PaperIndexer.constructAnalyzer(prop.getProperty("stopfile"));        
    }
    
    /**
     * Called to display per-feature related information on the event
     * of clicking on a table from the web interface.
     */
    public PassageRetriever() throws Exception {
        String indexPath = this.getClass().getClassLoader().getResource("indexes/para.index.all").getPath();
        String stopFileName = this.getClass().getClassLoader().getResource("stop.txt").getPath();

        File indexDir = new File(indexPath);
        reader = DirectoryReader.open(FSDirectory.open(indexDir.toPath()));
        searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new LMJelinekMercerSimilarity(0.6f));

        analyzer = PaperIndexer.constructAnalyzer(stopFileName);        
        
    }
    
    public void close() {
        try {
            reader.close();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    public IndexSearcher getSearcher() { return searcher; }
    public Analyzer getAnalyzer() { return analyzer; }
    
    /**
     * Builds a query object and returns 'nwanted' top most similar passages.
     * @param query A query string pertaining to an attribute.
     * @param nwanted Number of top passages to retrieve
     * @return Lucene 'TopDocs' object.
     */
    public TopDocs retrieve(String query, int nwanted) throws Exception {
        Query q = buildQuery(query);
        return searcher.search(q, nwanted);        
    }

    /**
     * Given a query, returns 'nwanted' top most similar passages.
     * @param query Specified Lucene 'Query' object.
     * @param nwanted Number of top passages to retrieve
     * @return A Lucene 'TopDocs' object.
     */
    public TopDocs retrieve(Query query, int nwanted) throws Exception {
        return searcher.search(query, nwanted);        
    }
    
    public String analyze(String query) throws Exception {
        StringBuffer buff = new StringBuffer();
        TokenStream stream = analyzer.tokenStream("dummy", new StringReader(query));
        CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
        stream.reset();
        while (stream.incrementToken()) {
            String term = termAtt.toString();
            buff.append(term).append(" ");
        }
        stream.end();
        stream.close();
        return buff.toString();
    }

    /**
     * Given a query string, tokenizes it and builds a Lucene BooleanQuery object.
     * @param queryStr Query string
     * @return Lucene 'Query' object.
     */
    public Query buildQuery(String queryStr) throws Exception {
        BooleanQuery q = new BooleanQuery();
        Term thisTerm = null;
        Query tq = null;
        String[] queryWords = analyze(queryStr).split("\\s+");

        // search in title and content...
        for (String term : queryWords) {
            thisTerm = new Term(ResearchDoc.FIELD_CONTENT, term);
            tq = new TermQuery(thisTerm);
            q.add(tq, BooleanClause.Occur.SHOULD);
        }
        return q;
    }    
}
