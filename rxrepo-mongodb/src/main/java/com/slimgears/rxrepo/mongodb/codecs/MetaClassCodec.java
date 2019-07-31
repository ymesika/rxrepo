package com.slimgears.rxrepo.mongodb.codecs;

import com.google.common.base.Strings;
import com.slimgears.rxrepo.annotations.Searchable;
import com.slimgears.rxrepo.mongodb.ReferencedObjectResolver;
import com.slimgears.rxrepo.util.PropertyMetas;
import com.slimgears.util.autovalue.annotations.*;
import com.slimgears.util.reflect.TypeToken;
import com.slimgears.util.stream.Lazy;
import com.slimgears.util.stream.Optionals;
import com.slimgears.util.stream.Streams;
import org.bson.*;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class MetaClassCodec<T extends HasMetaClass<T>> implements Codec<T> {
    private final static Transformer emptyTransformer = obj -> obj;
    private final static DecoderContext defaultDecoderContext = DecoderContext.builder().build();
    private final static EncoderContext defaultEncoderContext = EncoderContext.builder().build();
    private final MetaClass<T> metaClass;
    private final CodecRegistry codecRegistry;
    private final Lazy<Optional<Function<T, String>>> textSupplier;
    private final ReferencedObjectResolver objectResolver;
    private final boolean alwaysEmbedNested;

    private MetaClassCodec(Class<T> clazz, CodecRegistry codecRegistry,
                           ReferencedObjectResolver objectResolver) {
        this.metaClass = MetaClasses.forClass(clazz);
        this.objectResolver = objectResolver;
        this.alwaysEmbedNested = objectResolver == null;
        this.textSupplier = Lazy.of(this::searchableTextFromEntity);
        this.codecRegistry = codecRegistry;
    }

    static <T extends HasMetaClass<T>> Codec<T> create(Class<T> clazz, CodecRegistry codecRegistry, ReferencedObjectResolver objectResolver) {
        return new MetaClassCodec<>(clazz, codecRegistry, objectResolver);
    }

    static <T extends HasMetaClass<T>> Codec<T> create(Class<T> clazz, CodecRegistry codecRegistry) {
        return new MetaClassCodec<>(clazz, codecRegistry, null);
    }

    public static String fieldName(PropertyMeta<?, ?> propertyMeta) {
        return PropertyMetas.isKey(propertyMeta) ? "_id" : propertyMeta.name();
    }

    public static String referenceFieldName(PropertyMeta<?, ?> propertyMeta) {
        return propertyMeta.name() + "__ref";
    }

    private static boolean isReferenceFieldName(String name) {
        return name.endsWith("__ref");
    }

    @Override
    public T decode(BsonReader reader, DecoderContext decoderContext) {
        reader.readStartDocument();
        MetaBuilder<T> builder = metaClass.createBuilder();
        int foundProperties = 0;
        while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
            String name = reader.readName();
            Function<String, PropertyMeta<T, ?>> propertyGetter;
            Consumer<PropertyMeta<T, ?>> propertyReader;

            if (isReferenceFieldName(name)) {
                propertyGetter = this::fromReferenceFieldName;
                propertyReader = prop -> readReferenceProperty(reader, prop, builder, decoderContext);
            } else {
                propertyGetter = this::fromFieldName;
                propertyReader = prop -> readProperty(reader, prop, builder, decoderContext);
            }

            PropertyMeta<T, ?> prop = propertyGetter.apply(name);
            if (prop != null) {
                if (reader.getCurrentBsonType() == BsonType.NULL) {
                    reader.readNull();
                } else {
                    propertyReader.accept(prop);
                }
                ++foundProperties;
            } else {
                reader.skipValue();
            }
        }
        reader.readEndDocument();
        return (foundProperties > 0)
                ? builder.build()
                : null;
    }

    @Override
    public void encode(BsonWriter writer, T value, EncoderContext encoderContext) {
        writer.writeStartDocument();
        metaClass.properties()
                .forEach(p -> writeProperty(writer, p, value, encoderContext));
        textSupplier.get()
                .ifPresent(func -> {
                    String text = func.apply(value);
                    writer.writeName("_text");
                    writer.writeString(text);
                });
        writer.writeEndDocument();
    }

    @Override
    public Class<T> getEncoderClass() {
        return metaClass.asClass();
    }

    private PropertyMeta<T, ?> fromFieldName(String fieldName) {
        return "_id".equals(fieldName)
                ? Optional.of(metaClass)
                .map(mc -> mc instanceof MetaClassWithKey
                        ? (MetaClassWithKey<?, T>)mc
                        : null)
                .map(HasKeyProperty::keyProperty)
                .orElse(null)
                : metaClass.getProperty(fieldName);
    }

    private PropertyMeta<T, ?> fromReferenceFieldName(String fieldName) {
        fieldName = isReferenceFieldName(fieldName)
                ? fieldName.substring(0, fieldName.length() - 5)
                : fieldName;
        return fromFieldName(fieldName);
    }

    private <V> void writeProperty(BsonWriter writer, PropertyMeta<T, V> propertyMeta, T object, EncoderContext context) {
        V val = propertyMeta.getValue(object);
        if (val != null) {
            if (!alwaysEmbedNested && PropertyMetas.isReference(propertyMeta)) {
                writer.writeName(referenceFieldName(propertyMeta));
                MetaClassWithKey<?, V> metaClass = MetaClasses.forTokenWithKeyUnchecked(propertyMeta.type());
                writeReference(writer, metaClass, val, context);
            } else {
                writer.writeName(fieldName(propertyMeta));
                writeValue(writer, propertyMeta.type().asClass(), val, context);
            }
        }
    }

    private <K, V> void writeReference(BsonWriter writer, MetaClassWithKey<K, V> metaClassWithKey, V value, EncoderContext context) {
        writeValue(writer, metaClassWithKey.keyProperty().type().asClass(), metaClassWithKey.keyOf(value), context);
    }

    private <V> void writeValue(BsonWriter writer, Class<V> valueClass, V value, EncoderContext context) {
        Codec<V> codec = codecRegistry.get(valueClass);
        codec.encode(writer, value, context);
    }

    private <V> void readProperty(BsonReader reader, PropertyMeta<T, V> propertyMeta, MetaBuilder<T> builder, DecoderContext context) {
        Optional.ofNullable(readValue(reader, propertyMeta.type(), context))
                .ifPresent(val -> propertyMeta.setValue(builder, val));
    }

    private <V> V readValue(BsonReader reader, TypeToken<V> type, DecoderContext context) {
        Transformer transformer = ContextValueTransformer.current();
        if (type.is(Iterable.class::isAssignableFrom)) {
            TypeToken<?> argType = type.typeArguments()[0];
            transformer = transformerFor(argType);
        }
        return ContextValueTransformer
                .withTransformer(transformer, () -> {
                    Codec<V> codec = codecRegistry.get(type.asClass());
                    return Optional.ofNullable(codec.decode(reader, context));
                })
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private <V> void readReferenceProperty(BsonReader reader, PropertyMeta<T, V> propertyMeta, MetaBuilder<T> builder, DecoderContext context) {
        MetaClassWithKey<?, V> metaClass = MetaClasses.forTokenWithKeyUnchecked(propertyMeta.type());
        Optional.ofNullable((V)readReference(reader, (MetaClassWithKey)metaClass, context))
                .ifPresent(val -> propertyMeta.setValue(builder, val));
    }

    private <K, S extends HasMetaClassWithKey<K, S>> S readReference(BsonReader reader, MetaClassWithKey<K, S> metaClass, DecoderContext context) {
        return Optional.ofNullable(readValue(reader, metaClass.keyProperty().type(), context))
                .map(key -> objectResolver.resolve(metaClass, key))
                .orElse(null);
    }

    private <R> Transformer transformerFor(TypeToken<R> type) {
        if (!PropertyMetas.hasMetaClass(type)) {
            return emptyTransformer;
        }

        return embeddedObjectTransformer(MetaClasses.forTokenUnchecked(type));
    }

    private <R> Transformer embeddedObjectTransformer(MetaClass<R> metaClass) {
        Codec<R> codec = codecRegistry.get(metaClass.asClass());
        return obj -> Optional.ofNullable(obj)
                .flatMap(Optionals.ofType(Bson.class))
                .map(bson -> bson.toBsonDocument(BsonDocument.class, codecRegistry))
                .map(bson -> codec.decode(bson.asBsonReader(), defaultDecoderContext))
                .orElse(null);
    }

    private <K, S extends HasMetaClassWithKey<K, S>> Transformer referenceObjectTransformer(MetaClassWithKey<K, S> metaClass) {
        Codec<K> codec = codecRegistry.get(metaClass.keyProperty().type().asClass());
        return obj -> Optional.ofNullable(obj)
                .flatMap(Optionals.ofType(Bson.class))
                .map(bson -> bson.toBsonDocument(BsonDocument.class, codecRegistry))
                .map(bson -> codec.decode(bson.asBsonReader(), defaultDecoderContext))
                .map(key -> objectResolver.resolve(metaClass, key))
                .orElse(null);
    }

    private Optional<Function<T, String>> searchableTextFromEntity() {
        return searchableTextFromEntity(metaClass, e -> e, new HashSet<>());
    }

    private <R> Optional<Function<T, String>> searchableTextFromEntity(MetaClass<R> metaClass, Function<T, R> getter, Set<PropertyMeta<?, ?>> visitedProperties) {
        Optional<Function<T, String>> selfFields = searchableTextForMetaClass(metaClass, visitedProperties).map(getter::andThen);
        Optional<Function<T, String>> nestedFields = Streams
                .fromIterable(metaClass.properties())
                .filter(p -> p.type().is(HasMetaClass.class::isAssignableFrom))
                .filter(visitedProperties::add)
                .map(p -> searchableTextFromProperty(getter, p, visitedProperties))
                .flatMap(o -> o.map(Stream::of).orElseGet(Stream::empty))
                .reduce(MetaClassCodec::combine);

        if (selfFields.isPresent() && nestedFields.isPresent()) {
            return Optional.of(combine(selfFields.get(), nestedFields.get()));
        } else if (selfFields.isPresent()) {
            return selfFields;
        } else {
            return nestedFields;
        }
    }

    private <R, V> Optional<Function<T, String>> searchableTextFromProperty(Function<T, R> getter, PropertyMeta<R, V> propertyMeta, Set<PropertyMeta<?, ?>> visitedProperties) {
        Function<T, V> nextGetter = val -> Optional.ofNullable(getter.apply(val)).map(propertyMeta::getValue).orElse(null);
        MetaClass<V> metaClass = MetaClasses.forTokenUnchecked(propertyMeta.type());
        return searchableTextFromEntity(metaClass, nextGetter, visitedProperties);
    }

    private static <T> Function<T, String> combine(Function<T, String> first, Function<T, String> second) {
        return entity -> combineStrings(first.apply(entity), second.apply(entity));
    }

    private static <T> Optional<Function<T, String>> searchableTextForMetaClass(MetaClass<T> metaClass, Set<PropertyMeta<?, ?>> visitedProperties) {
        return Streams
                .fromIterable(metaClass.properties())
                .filter(p -> p.hasAnnotation(Searchable.class))
                .filter(visitedProperties::add)
                .<Function<T, String>>map(p -> (entity -> Optional
                        .ofNullable(entity)
                        .map(p::getValue)
                        .map(Object::toString)
                        .orElse("")))
                .reduce(MetaClassCodec::combine);
    }

    private static String combineStrings(String first, String second) {
        if (Strings.isNullOrEmpty(first)) {
            return second != null ? second : "";
        }
        if (Strings.isNullOrEmpty(second)) {
            return first;
        }
        return first + " " + second;
    }
}