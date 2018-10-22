/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package nb;

import extractor.AbstractDetectPresenceAttribute;
import extractor.CVSplit;
import extractor.InformationExtractor;
import extractor.InformationUnit;
import indexer.PaperIndexer;
import indexer.ResearchDoc;
import static indexer.ResearchDoc.FIELD_AUTHORS;
import indexer.ShingleAnalyzer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import org.apache.commons.exec.ExecuteException;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.classification.ClassificationResult;
import org.apache.lucene.classification.Classifier;
import org.apache.lucene.classification.KNearestNeighborClassifier;
import org.apache.lucene.classification.SimpleNaiveBayesClassifier;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SlowCompositeReaderWrapper;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ref.Attribute;
import ref.CodeSetTree;
import ref.CodeSetTreeNode;
import ref.PerDocRefs;

/**
 * Train a Naive Bayes classifier: pos-samples - the annotated text from training set
 * neg-samples: passages retrieved from text having no annotations
 * 
 * After training the classifier model, pass the top N passages through
 * the classifier and compute the predicted class as the max (predicted classes).
 * 
 * @author dganguly
 */
public class DPAttribNBClassifier {
    SimpleNaiveBayesClassifier classifier;
    CVSplit cvsplit;
    Analyzer analyzer;
    Properties prop;
    InformationUnit iunit;
    
    static Logger logger = LoggerFactory.getLogger(DPAttribNBClassifier.class);
    
    public static String FIELD_CLASS_LABEL = "label";
    
    public DPAttribNBClassifier(Properties prop, CVSplit cvsplit, InformationUnit iunit) throws Exception {
        this.prop = prop;
        classifier = new SimpleNaiveBayesClassifier();
        //classifier = new KNearestNeighborClassifier(5);
        
        this.cvsplit = cvsplit;
        this.iunit = iunit;
        analyzer = PaperIndexer.constructAnalyzer(this.getClass().getClassLoader().getResource("stop.txt").getPath());        
        //analyzer = new ShingleAnalyzer(prop);
    }
    
    public DPAttribNBClassifier(SimpleNaiveBayesClassifier classifier) {
        this.classifier = classifier;
    }
    
    public void train() throws Exception {
        Directory ramdir = new RAMDirectory();                
        IndexWriterConfig iwcfg = new IndexWriterConfig(analyzer);
        IndexWriter writer = new IndexWriter(ramdir, iwcfg);
        
        loadPositiveSamples(writer);
        loadNegativeSamples(writer);
        
        writer.commit();
        writer.close();
                
        IndexReader trainIndexReader = DirectoryReader.open(ramdir);
        LeafReader lreader = SlowCompositeReaderWrapper.wrap(trainIndexReader);
        
        printTrainingSet(trainIndexReader);
        
        // Train the NB classifier
        classifier.train(lreader, ResearchDoc.FIELD_CONTENT, FIELD_CLASS_LABEL, analyzer);
    }

    void printTrainingSet(IndexReader trainIndexReader) throws Exception {
        logger.info("Training NB classifier on dataset: ");
        int n = trainIndexReader.numDocs();
        for (int i=0; i < n; i++) {
            Document doc = trainIndexReader.document(i);
            String analyzedContent = PaperIndexer.analyze(analyzer, doc.get(ResearchDoc.FIELD_CONTENT));
            logger.debug(analyzedContent + "\t" + doc.get(FIELD_CLASS_LABEL));
        }
    }
    
    void addExample(IndexWriter writer, String text, int label) throws Exception {
        Document doc = new Document();
        doc.add(new Field(ResearchDoc.FIELD_CONTENT, text,
            Field.Store.YES, Field.Index.ANALYZED, Field.TermVector.NO));  // content
        doc.add(new Field(FIELD_CLASS_LABEL, String.valueOf(label),
            Field.Store.YES, Field.Index.NOT_ANALYZED, Field.TermVector.NO));  // label
        
        writer.addDocument(doc);
    }
    
    // Build the training set
    void loadPositiveSamples(IndexWriter writer) throws Exception {

        // Write out positive samples
        for (Attribute a : cvsplit.getAttribsToTrain()) {
            addExample(writer, a.getContextText() + " " + a.getAnnotatedText(), 1);
        }
    }
        
    // Write out the negative samples
    void loadNegativeSamples(IndexWriter writer) throws Exception {
        
        List<Integer> unannotatedDocsInTrainingSet = cvsplit.getUnannotatedDocIds();
        
        InformationExtractor extractor = iunit.getExtractor();
        
        // the threshold is not gonna be used for negative sampling...
        // also zeroing out here is safe because the threshold is not gonna be
        // used in this flow... log may show zero values
        ((AbstractDetectPresenceAttribute)iunit).setThreshold(0);
        
        int negativeSamplingWindowSize = Integer.parseInt(prop.getProperty("negativesampling.windowsize", "30"));
        int numwanted = Integer.parseInt(prop.getProperty("numwanted", "1"));
        
        for (int docId: unannotatedDocsInTrainingSet) {
            
            Directory inMemParaIndex = extractor.buildParaIndex(docId, negativeSamplingWindowSize);
            IndexReader paraReader = DirectoryReader.open(inMemParaIndex);
            
            // last parameter is false because the classifier hasn't been trained yet
            InformationUnit predicted = extractor.extractInformationFromDoc(paraReader, docId, iunit, false);
            if (predicted!=null) {
                predicted.setProperties();
                String negativeText = predicted.getBestAnswer().getKey();
                addExample(writer, negativeText, 0);
            }
        }
    }
    
    // inputText - the top retrieved passage
    // Apply NB classifier to predict output.
    public int getPrediction(String inputText) throws Exception {
        
        ClassificationResult<BytesRef> result = classifier.assignClass(inputText);
        int predClass = Integer.parseInt(result.getAssignedClass().utf8ToString());
        
        logger.debug("NB-output (" + inputText + ") = " + predClass);
        
        return predClass;
    }
    
    public SimpleNaiveBayesClassifier getClassifier() { return classifier; }    
}
