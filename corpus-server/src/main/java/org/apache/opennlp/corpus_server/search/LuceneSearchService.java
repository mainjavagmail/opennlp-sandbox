/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.opennlp.corpus_server.search;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.opennlp.corpus_server.CorpusServer;
import org.apache.opennlp.corpus_server.UimaUtil;
import org.apache.opennlp.corpus_server.store.CorporaStore;
import org.apache.opennlp.corpus_server.store.CorpusStore;
import org.apache.uima.UIMAFramework;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.Type;
import org.apache.uima.lucas.indexer.IndexWriterProviderImpl;
import org.apache.uima.resource.FileResourceSpecifier;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.impl.FileResourceSpecifier_impl;
import org.apache.uima.resource.metadata.MetaDataObject;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.util.CasCreationUtils;
import org.apache.uima.util.InvalidXMLException;
import org.apache.uima.util.XMLInputSource;

public class LuceneSearchService implements SearchService {

  private final static Logger LOGGER = Logger.getLogger(
      LuceneSearchService.class .getName());
  
  private Map<String, AnalysisEngine> corpusIndexerMap = new HashMap<String, AnalysisEngine>();
  
  // create a map with corpus name and indexer ae ...
  // indexer ae is a pair of ae and descriptor (maybe that can be done nicer)

  private static File getIndexDirectory(String corpusId) {
    return new File("index" + File.separator + corpusId);
  }
  
  private void createIndexWriter(String corpusId, boolean createIndex) throws IOException {
    
    // Set the index mapping file for this corpus in the analysis engine descriptor
    CorpusStore corpusStore = CorpusServer.getInstance().getStore().getCorpus(corpusId);
    
    XMLInputSource in = new XMLInputSource(new ByteArrayInputStream(corpusStore.getIndexMapping()), new File(""));
    
    try {
      AnalysisEngineDescription specifier;
      specifier = (AnalysisEngineDescription) UIMAFramework.getXMLParser().parseResourceSpecifier(in);
      
      // TODO: How to store mapping file? Should be transmitted during corpus creation ...

      File mappingTmpFile = File.createTempFile("lucas-mapping", corpusId + ".xml");
      mappingTmpFile.deleteOnExit();
      
      InputStream mappingFileIn = null;
      OutputStream mappingTmpOut = null;
      
      try {
        mappingFileIn = LuceneSearchService.class.getResourceAsStream(
            "/org/apache/opennlp/corpus_server/search/" + corpusId + ".xml");
        mappingTmpOut = new FileOutputStream(mappingTmpFile);
        
        byte buffer[] = new byte[1024];
        int len = 0;
        while ((len = mappingFileIn.read(buffer)) > 0) {
          mappingTmpOut.write(buffer, 0, len);
        }
      }
      catch (IOException e) {
        // TODO: Or just ignore it ?! and do not create the indexer for this corpus?!
        throw e;
      }
      finally {
        if (mappingFileIn != null) {
          try {
            mappingFileIn.close();
          }
          catch (IOException e) {}
        }
        
        if (mappingTmpOut != null) {
          try {
            mappingTmpOut.close();
          }
          catch (IOException e) {}
        }
      }
      
      
      specifier.getAnalysisEngineMetaData().
      getConfigurationParameterSettings().setParameterValue("mappingFile",
          mappingTmpFile.getAbsolutePath());
      
      // Set the index writer properties file in the analysis engine
      // and replace the index path with the index location for this corpus
      
      Properties indexWriterProperties = new Properties();
      
      InputStream indexWriterPropertiesIn = null;
      try {
        // TODO: Retrieve file form somewhere for this corpus
        indexWriterPropertiesIn = LuceneSearchService.class.getResourceAsStream(
            "/org/apache/opennlp/corpus_server/search/IndexWriter.properties");
      
        indexWriterProperties.load(indexWriterPropertiesIn);
      }
      finally {
        if (indexWriterPropertiesIn != null) {
          try {
            indexWriterPropertiesIn.close();
          }
          catch (IOException e) {}
        }
      }
      
      indexWriterProperties.setProperty(IndexWriterProviderImpl.INDEX_PATH_PROPERTY,
          getIndexDirectory(corpusId).getAbsolutePath());
      
      indexWriterProperties.setProperty(IndexWriterProviderImpl.CREATE_INDEX_PROPERTY,
          Boolean.toString(createIndex));
      
      File indexWriterTmpFile = File.createTempFile("index-writer", corpusId + ".properties");
      indexWriterTmpFile.deleteOnExit();
      
      OutputStream indexPropertiesOut = null; 
      try {
        indexPropertiesOut = new FileOutputStream(indexWriterTmpFile);
        // write properties into a tmp file
        indexWriterProperties.store(indexPropertiesOut, null);
      }
      finally {
        if (indexPropertiesOut != null) {
          try {
            indexPropertiesOut.close();
          }
          catch (IOException e) {}
        }
      }
      
      FileResourceSpecifier indexWriterFileSpecifier = new FileResourceSpecifier_impl();
      indexWriterFileSpecifier.setFileUrl(indexWriterTmpFile.toURL().toString());
      // TODO: This will fail ...
      specifier.getResourceManagerConfiguration().getExternalResources()[0].setResourceSpecifier(indexWriterFileSpecifier);
      
      AnalysisEngine indexer = UIMAFramework.produceAnalysisEngine(specifier);
      corpusIndexerMap.put(corpusId, indexer);
    } catch (InvalidXMLException e) {
      throw new IOException(e);
    } catch (ResourceInitializationException e) {
      throw new IOException(e);
    }
  }
  
  @Override
  public synchronized void initialize(CorporaStore corporaStore) throws IOException {
    
    for (String corpusId : corporaStore.getCorpusIds()) {
      createIndexWriter(corpusId, false);
      LOGGER.info("Created Index Writer for " + corpusId + "corpus.");
    }
  }
  
  @Override
  public synchronized void createIndex(CorpusStore store) throws IOException {
    createIndexWriter(store.getCorpusId(), true);
    LOGGER.info("Created Index Writer for " + store.getCorpusId() + " corpus.");
  }
  
  @Override
  public synchronized void index(CorpusStore store, String casId) throws IOException {
    
    // TODO: Need to take care for thread safety ..
    
    String corpusId = store.getCorpusId();
    
    AnalysisEngine indexer = corpusIndexerMap.get(corpusId);
    
    
    InputStream indexTsIn = LuceneSearchService.class.getResourceAsStream(
        "/org/apache/opennlp/corpus_server/search/TypeSystem.xml");
    
    TypeSystemDescription indexTypeDesc;
    try {
      indexTypeDesc = UimaUtil.createTypeSystemDescription(indexTsIn);
    }
    finally {
      indexTsIn.close();
    }
    
    List<MetaDataObject> specs = new ArrayList<MetaDataObject>();
    specs.add(indexTypeDesc);
    specs.add(store.getTypeSystem());
    
    // Note: This might be a performance problem
    CAS cas;
    try {
      cas = CasCreationUtils.createCas(specs);
    } catch (ResourceInitializationException e) {
      throw new IOException(e);
    }
    
    byte[] casBytes = store.getCAS(casId);
    
    UimaUtil.deserializeXmiCAS(cas, new ByteArrayInputStream(casBytes));
    
    // Inject id feature structure into the CAS
    Type casIdType = cas.getTypeSystem().getType(LuceneIndexer.CAS_ID_TYPE);
    Feature casIdFeature =  casIdType.getFeatureByBaseName(LuceneIndexer.CAS_ID_FEEATURE);

    FeatureStructure casIdFS = cas.createFS(casIdType);
    casIdFS.setStringValue(casIdFeature, casId);
    cas.addFsToIndexes(casIdFS);
    
    try {
      indexer.process(cas);
    } catch (AnalysisEngineProcessException e) {
      LOGGER.log(Level.SEVERE, "Failed to index CAS: " + casId, e);
    }
    
//    System.out.println("Index: " + casId);
  }

  @Override
  public List<String> search(CorpusStore store, String q)
      throws IOException {
    
    // TODO:
    // Creating a reader per query is can only be done for testing this ... 
    // Open/Reopen index once in a while to see updates, can this be done automatically?!
    
    File indexLocation = getIndexDirectory(store.getCorpusId());
    
    QueryParser parser = null;
    
    final IndexSearcher searcher = new IndexSearcher(indexLocation.getAbsolutePath());
    parser = new QueryParser("text", new WhitespaceAnalyzer());
    
    Query query;
    try {
      query = parser.parse(q);
    } catch (ParseException e) {
      throw new IOException(e);
    }
    
    final List<String> results = new ArrayList<String>();
    
    // query index ...
    searcher.search(query, new Collector() {
      
      int docBase = Integer.MIN_VALUE;
      
      @Override
      public void setScorer(Scorer scorer) throws IOException {
      }
      
      @Override
      public void setNextReader(IndexReader reader, int docBase) throws IOException {
        this.docBase = docBase;
      }
      
      @Override
      public void collect(int id) throws IOException {
        Document doc = searcher.doc(docBase + id);
        String idString = doc.get("id");
        results.add(idString);
      }
      
      @Override
      public boolean acceptsDocsOutOfOrder() {
        return false;
      }
    });
      
    searcher.close();
    
    return results;
  }

  @Override
  public void shutdown() throws IOException {
    
    for (String corpusId : corpusIndexerMap.keySet()) {
      AnalysisEngine indexer = corpusIndexerMap.get(corpusId);
      
      if (indexer != null) {
        indexer.destroy();
      }
    }
  }
}
