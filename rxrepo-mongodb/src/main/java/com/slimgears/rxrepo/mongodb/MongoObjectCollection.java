package com.slimgears.rxrepo.mongodb;

import com.mongodb.DuplicateKeyException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.AggregatePublisher;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.slimgears.rxrepo.expressions.Aggregator;
import com.slimgears.rxrepo.query.Notification;
import com.slimgears.rxrepo.query.provider.DeleteInfo;
import com.slimgears.rxrepo.query.provider.QueryInfo;
import com.slimgears.rxrepo.util.PropertyMetas;
import com.slimgears.util.autovalue.annotations.HasMetaClassWithKey;
import com.slimgears.util.autovalue.annotations.MetaClassWithKey;
import com.slimgears.util.reflect.TypeToken;
import com.slimgears.util.stream.Lazy;
import com.slimgears.util.stream.Optionals;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.functions.Function;
import org.bson.*;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class MongoObjectCollection<K, S extends HasMetaClassWithKey<K, S>> {
    private final static Logger log = LoggerFactory.getLogger(MongoObjectCollection.class);
    private final MetaClassWithKey<K, S> metaClass;
    private final Lazy<MongoCollection<Document>> objectCollection;
    private final Lazy<MongoCollection<Document>> notificationCollection;
    private final Lazy<Codec<S>> codec;
    private final Lazy<Codec<Document>> docCodec;
    private final CodecRegistry codecRegistry;

    MongoObjectCollection(MetaClassWithKey<K, S> metaClass, MongoDatabase database) {
        this.metaClass = metaClass;
        this.codecRegistry = database.getCodecRegistry();
        this.codec = Lazy.of(() -> codecRegistry.get(metaClass.asClass()));
        this.docCodec = Lazy.of(() -> codecRegistry.get(Document.class));
        this.objectCollection = Lazy.of(() -> database.getCollection(metaClass.simpleName()));
        this.notificationCollection = Lazy.of(() -> database.getCollection(metaClass.simpleName() + ".updates"));
    }

    private Maybe<Document> findDocument(K key) {
        return Observable.fromPublisher(objectCollection.get()
                .aggregate(MongoPipeline.builder()
                        .lookupAndUnwindReferences(metaClass)
                        .match(MongoPipeline.filterForKey(key))
                        .limit(1L)
                        .build()))
                .firstElement();
    }

    Maybe<S> insertOrUpdate(K key, Function<Maybe<S>, Maybe<S>> update) {
        AtomicLong version = new AtomicLong();
        AtomicReference<S> oldObject = new AtomicReference<>();
        AtomicReference<S> newObject = new AtomicReference<>();
        AtomicReference<Document> oldDoc = new AtomicReference<>();
        AtomicReference<Document> newDoc = new AtomicReference<>();
        return findDocument(key)
                .doOnSuccess(oldDoc::set)
                .doOnSuccess(doc -> version.set(doc.getLong(MongoPipeline.versionField)))
                .map(this::objectFromDocument)
                .doOnSuccess(oldObject::set)
                .map(Maybe::just)
                .flatMap(update)
                .doOnSuccess(newObject::set)
                .map(obj -> objectToDocument(obj, version.get() + 1))
                .doOnSuccess(newDoc::set)
                .doOnSuccess(doc -> log.trace("Updating object: {}", doc))
                .flatMap(doc -> Single
                        .fromPublisher(objectCollection.get()
                                .replaceOne(
                                        MongoPipeline.filterForKeyAndVersion(key, version.get()),
                                        doc))
                        .doOnSuccess(res -> log.trace("Update result: {}", res))
                        .map(UpdateResult::getMatchedCount)
                        .flatMapMaybe(c -> c == 1
                                ? Maybe.just(newObject.get())
                                : Maybe.error(
                                        new ConcurrentModificationException("Concurrent modification detected: version " +
                                                version.get() +
                                                " of object (id: " + key + ") not found")))
                        .flatMap(obj -> publish(oldDoc.get(), newDoc.get())
                                .andThen(Maybe.just(obj))))
                .switchIfEmpty(Maybe
                        .defer(() -> update.apply(Maybe.empty()))
                        .doOnSuccess(newObject::set)
                        .doOnSuccess(doc -> log.trace("Creating new object: {}", doc))
                        .map(obj -> objectToDocument(obj, version.get()))
                        .flatMap(doc -> Single.fromPublisher(objectCollection.get()
                                .insertOne(doc))
                                .doOnSuccess(res -> log.trace("Insert result: {}", res))
                                .toMaybe()
                                .map(res -> newObject.get())
                                .onErrorResumeNext((Throwable e) -> Maybe.error(convertError(e)))))
                .doOnSuccess(obj -> log.trace("Final object after update/insert: {}", obj))
                .doOnError(e -> log.trace("Could not update object: ", e));
    }

    <T> Observable<T> query(QueryInfo<K, S, T> query) {
        return queryDocuments(query)
                .doOnNext(doc -> log.debug("Retrieved document: {}", doc))
                .map(objectFromDocument(query.objectType()));
    }

    @SuppressWarnings("unchecked")
    <T, R> Maybe<R> aggregate(QueryInfo<K, S, T> query, Aggregator<T, T, R> aggregator) {
        AggregatePublisher<Document> publisher = objectCollection.get()
                .aggregate(MongoPipeline.aggregationPipeline(query, aggregator));

        TypeToken<R> resultType = aggregator.objectType(query.objectType());
        return Observable.fromPublisher(publisher)
                .doOnNext(doc -> log.debug("Retrieved document: {}", doc))
                .map(doc -> doc.get(MongoPipeline.aggregationField))
                .doOnNext(doc -> log.debug("Aggregation result: {}", doc))
                .map(obj -> obj instanceof Document
                        ? objectFromDocument(resultType).apply((Document)obj)
                        : (R)obj)
                .firstElement();
    }

    private Observable<Document> queryDocuments(QueryInfo<K, S, ?> query) {
        return Observable.fromPublisher(objectCollection.get()
                .aggregate(MongoPipeline.aggregationPipeline(query)));
    }

    Observable<Notification<S>> liveQuery() {
        Observable<Notification<S>> modifications = Observable.fromPublisher(notificationCollection.get().watch())
                .map(ChangeStreamDocument::getFullDocument)
                .map(this::notificationFromDocument);

        Observable<Notification<S>> insertionsAndDeletions = Observable
                .fromPublisher(objectCollection
                        .get()
                        .watch())
                .doOnNext(d -> log.trace("Change detected: {}", d))
                .flatMapMaybe(this::notificationFromChangeDocument);

        return modifications.mergeWith(insertionsAndDeletions);
    }

    private Completable publish(Document oldDoc, Document newDoc) {
        return Completable.fromPublisher(notificationCollection.get()
                .insertOne(createNotification(oldDoc, newDoc)));
    }

    private Document createNotification(Document oldDoc, Document newDoc) {
        Object id = Optionals.or(
                () -> Optional.ofNullable(newDoc),
                () -> Optional.ofNullable(oldDoc))
                .map(doc -> doc.get("_id"))
                .orElse(null);

        return new Document("key", id)
                .append("oldValue", oldDoc)
                .append("newValue", newDoc);
    }

    private Completable publishNotification(Document notificationDocument) {
        return Completable
                .fromPublisher(notificationCollection.get()
                .insertOne(notificationDocument));
    }

    Single<Integer> delete(DeleteInfo<K, S> deleteInfo) {
        return queryDocuments(QueryInfo
                .<K, S, S>builder()
                .metaClass(deleteInfo.metaClass())
                .predicate(deleteInfo.predicate())
                .limit(deleteInfo.limit())
                .build())
                .map(doc -> createNotification(doc, doc))
                .flatMapCompletable(this::publishNotification)
                .andThen(Observable.fromPublisher(objectCollection.get()
                        .deleteMany(MongoPipeline.expr(deleteInfo.predicate())))
                        .map(DeleteResult::getDeletedCount)
                        .firstElement()
                        .map(Long::intValue)
                        .toSingle(0));
    }

    private static Throwable convertError(Throwable e) {
        return (isDuplicateKeyException(e))
                ? new ConcurrentModificationException(e)
                : e;
    }

    private static boolean isDuplicateKeyException(Throwable e) {
        return e instanceof DuplicateKeyException ||
                (e instanceof MongoWriteException && ((MongoWriteException)e).getError().getCategory() == ErrorCategory.DUPLICATE_KEY);
    }

    private Notification<S> notificationFromDocument(Document document) {
        return Notification.ofModified(
                document.get("oldValue", metaClass.asClass()),
                document.get("newValue", metaClass.asClass()));
    }

    private Maybe<Notification<S>> notificationFromChangeDocument(ChangeStreamDocument<Document> changeDoc) {
        if (changeDoc.getOperationType() == OperationType.INSERT) {
            S object = Optional
                    .ofNullable(changeDoc.getFullDocument())
                    .map(this::objectFromDocument)
                    .orElse(null);
            return Maybe.just(Notification.ofCreated(object));
        } else if (changeDoc.getOperationType() == OperationType.DELETE) {
            Object key = Optional.of(changeDoc.getDocumentKey())
                    .map(doc -> doc.get("_id"))
                    .orElse(null);

            return Observable.fromPublisher(notificationCollection.get()
                    .aggregate(MongoPipeline.builder()
                            .match(MongoPipeline.filterForField("key", key))
                            .replaceRoot("$newValue")
                            .lookupAndUnwindReferences(metaClass)
                            .sort(new Document(MongoPipeline.versionField, "-1"))
                            .limit(1L)
                            .build()))
                    .firstElement()
                    .map(this::objectFromDocument)
                    .map(Notification::ofDeleted);
        }
        return Maybe.empty();
    }

    private Document objectToDocument(S obj, long version) {
        BsonDocument bson = new BsonDocument();
        codec.get().encode(new BsonDocumentWriter(bson), obj, EncoderContext.builder().build());
        bson.append(MongoPipeline.versionField, new BsonInt64(version));
        return fromBson(bson);
    }

    private S objectFromDocument(Document doc) {
        return codec.get().decode(toBson(doc).asBsonReader(), DecoderContext.builder().build());
    }

    private BsonDocument toBson(Document doc) {
        return doc.toBsonDocument(BsonDocument.class, codecRegistry);
    }

    private Document fromBson(BsonDocument bson) {
        return docCodec.get().decode(bson.asBsonReader(), DecoderContext.builder().build());
    }

    @SuppressWarnings("unchecked")
    private <T> Function<Document, T> objectFromDocument(TypeToken<T> objectType) {
        return doc -> {
            if (!PropertyMetas.hasMetaClass(objectType)) {
                return (T)doc.get(MongoPipeline.valueField);
            }
            BsonDocument bsonDoc = doc.toBsonDocument(BsonDocument.class, codecRegistry);
            BsonReader reader = bsonDoc.asBsonReader();
            Codec<T> codec = codecRegistry.get(objectType.asClass());
            return codec.decode(reader, DecoderContext.builder().build());
        };
    }

    private Document toDocument(BsonDocument bson) {
        return codecRegistry.get(Document.class).decode(bson.asBsonReader(), DecoderContext.builder().build());
    }
}