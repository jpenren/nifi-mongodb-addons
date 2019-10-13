/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nifi.processors.mongodb;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.JsonValidator;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.StringUtils;
import org.bson.Document;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

@Tags({"mongo", "get", "fetch"})
@CapabilityDescription("Creates FlowFiles from documents in MongoDB. This implementation differs from the standard GetMongo in that queries are executed using skip() and limit() to retrieve data paginated. NOTE: this is an stateful implementation, skip value is update with each execution. Stop component to restart the skip value.")
@WritesAttributes({
    @WritesAttribute(attribute=FetchMongo.ATTR_MIME_TYPE, description="Mime Type as application/json"),
    @WritesAttribute(attribute=FetchMongo.ATTR_DB_NAME, description="The database where the results came from."),
    @WritesAttribute(attribute = FetchMongo.ATTR_COL_NAME, description = "The collection where the results came from."),
    @WritesAttribute(attribute = FetchMongo.ATTR_CURRENT_PAGE, description = "The current page."),
    @WritesAttribute(attribute = FetchMongo.ATTR_PAGE_SIZE, description = "Items per page."),
    @WritesAttribute(attribute = "mongo.doc.[field]", description = "Fiels defined in Extract Fields property.")
    
})
public class FetchMongo extends AbstractProcessor {

    public static final String ATTR_PAGE_INDEX = "mongo.current.page.index";
    public static final String ATTR_CURRENT_PAGE = "mongo.current.page";
    public static final String ATTR_COL_NAME = "mongo.collection.name";
    public static final String ATTR_DB_NAME = "mongo.database.name";
    public static final String ATTR_PAGE_SIZE = "mongo.page.size";
    public static final String ATTR_MIME_TYPE = "mime.type";
    private static final int FIRST_PAGE = 0;
    
    public static final PropertyDescriptor URI = new PropertyDescriptor.Builder()
            .name("Mongo URI")
            .displayName("Mongo URI")
            .description("MongoURI, typically of the form: mongodb://host1[:port1][,host2[:port2],...]")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    private static final PropertyDescriptor DATABASE_NAME = new PropertyDescriptor.Builder()
            .name("Mongo Database Name")
            .displayName("Mongo Database Name")
            .description("The name of the database to use.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    private static final PropertyDescriptor COLLECTION_NAME = new PropertyDescriptor.Builder()
            .name("Mongo Collection Name")
            .description("The name of the collection to use.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor PAGE_SIZE = new PropertyDescriptor.Builder()
            .name("Page Size")
            .description("Number of elements per page. This property defines the value used within limit() method to specify the maximum number of documents the cursor will return.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .defaultValue("50")
            .build();
    
    private static final PropertyDescriptor CHARSET = new PropertyDescriptor.Builder()
            .name("mongo-charset")
            .displayName("Character Set")
            .description("Specifies the character set of the document data.")
            .required(true)
            .defaultValue("UTF-8")
            .addValidator(StandardValidators.CHARACTER_SET_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();
    
    public static final PropertyDescriptor PROJECTION = new PropertyDescriptor.Builder()
            .name("Projection")
            .description("The fields to be returned from the documents in the result set; must be a valid BSON document")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(JsonValidator.INSTANCE)
            .build();
    
    public static final PropertyDescriptor EXTRACT_FIELDS = new PropertyDescriptor.Builder()
            .name("Extract Fields")
            .description("The fields to be extracted as mongo.doc.[field] attributes, list of comma separated values")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("All FlowFiles that have the results of a successful query execution go here.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("All input FlowFiles that are part of a failed query execution go here.")
            .build();

    private final AtomicInteger currentPage = new AtomicInteger(FIRST_PAGE);
    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;
    private MongoClient mongoClient;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        this.descriptors = Collections.unmodifiableList(Arrays.asList(URI, DATABASE_NAME, COLLECTION_NAME, PROJECTION, PAGE_SIZE, CHARSET, EXTRACT_FIELDS));
        this.relationships = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(REL_SUCCESS, REL_FAILURE)));
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        closeClient();
        mongoClient = new MongoClient(new MongoClientURI(getURI(context)));
        getLogger().debug("Processor scheduled");
    }
    
    @OnStopped
    public void onStopped() {
        closeClient();
        currentPage.set(FIRST_PAGE);
        getLogger().debug("Processor stopped");
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        final ComponentLog logger = getLogger();
        final Document query = Document.parse("{}");
        final Document projection = parse(context, PROJECTION);
        final int pageSize = getProperty(context, PAGE_SIZE).asInteger();
        final Charset charset = Charset.forName(getProperty(context, CHARSET).getValue());
        
        final long startup = System.currentTimeMillis();
        final MongoCollection<Document> collection = getCollection(context);
        
        final Map<String, String> attributes = new HashMap<>();
        attributes.put(CoreAttributes.MIME_TYPE.key(), "application/json");
        attributes.put(ATTR_DB_NAME, collection.getNamespace().getDatabaseName());
        attributes.put(ATTR_COL_NAME, collection.getNamespace().getCollectionName());
        attributes.put(ATTR_PAGE_SIZE, String.valueOf(pageSize));
        
        final int skip = currentPage.getAndIncrement() * pageSize;
        logger.debug("-> find(*).skip({}).limit({})", new Object[] {skip, pageSize});
        final FindIterable<Document> it = collection.find(query).skip(skip).limit(pageSize);
        if (projection != null) {
            it.projection(projection);
        }

        long docs = 0;
        long index = 0;
        try (MongoCursor<Document> cursor = it.iterator()) {
            while (cursor.hasNext()) {
                FlowFile flowFile = session.create();
                flowFile = session.write(flowFile, out -> {
                    final Document document = cursor.next();
                    extractFields(context, document, attributes);
                    out.write(document.toJson().getBytes(charset));
                });
                
                attributes.put(ATTR_PAGE_INDEX, String.valueOf(index));
                attributes.put(ATTR_CURRENT_PAGE, String.valueOf(currentPage.get()));
                flowFile = session.putAllAttributes(flowFile, attributes);
                session.getProvenanceReporter().receive(flowFile, getURI(context));
                session.transfer(flowFile, REL_SUCCESS);
                index++;
                docs++;
            }
        }
        
        // current page has no elements, preserve current page
        if (docs == 0) {
            currentPage.decrementAndGet();
        }
        
        final long time = System.currentTimeMillis() - startup;
        logger.debug("docs: {}, page: {}, time: {}ms", new Object[]{docs, currentPage.get(), time});
    }
    
    private PropertyValue getProperty(ProcessContext context, PropertyDescriptor property) {
        return context.getProperty(property).evaluateAttributeExpressions();
    }

    private String getURI(final ProcessContext context) {
        return getProperty(context, URI).getValue();
    }
    
    protected MongoCollection<Document> getCollection(final ProcessContext context) {
        final String collectionName = getProperty(context, COLLECTION_NAME).getValue();
        if (StringUtils.isEmpty(collectionName)) {
            throw new ProcessException("Collection name was empty after expression language evaluation.");
        }
        
        final String databaseName = getProperty(context, DATABASE_NAME).getValue();

        return mongoClient.getDatabase(databaseName).getCollection(collectionName);
    }
    
    private Document parse(ProcessContext context, PropertyDescriptor descriptor) {
        if(context.getProperty(descriptor).isSet()) {
            return Document.parse(getProperty(context, descriptor).getValue());
        }
        
        return null;
    }
    
    private void extractFields(ProcessContext context, Document document, Map<String, String> attributes) {
        if(context.getProperty(EXTRACT_FIELDS).isSet()) {
            final String[] keys = getProperty(context, EXTRACT_FIELDS).getValue().replace(" ", "").split(",");
            for (String key : keys) {
                try {                                    
                    final Object value = document.get(key);
                    attributes.put("mongo.doc."+key, String.valueOf(value));
                } catch(Exception e) {
                    getLogger().error("Field {} not found", new Object[] {key});
                }
            }
        }
    }
    
    private void closeClient() {
        if(mongoClient!=null) {
            mongoClient.close();
            mongoClient = null;
        }
    }
    
}
