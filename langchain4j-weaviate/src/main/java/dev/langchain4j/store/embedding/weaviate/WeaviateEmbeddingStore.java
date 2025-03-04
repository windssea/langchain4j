package dev.langchain4j.store.embedding.weaviate;

import static dev.langchain4j.internal.Utils.generateUUIDFrom;
import static dev.langchain4j.internal.Utils.randomUUID;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.joining;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.weaviate.client.Config;
import io.weaviate.client.WeaviateAuthClient;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.base.WeaviateErrorMessage;
import io.weaviate.client.v1.auth.exception.AuthException;
import io.weaviate.client.v1.data.model.WeaviateObject;
import io.weaviate.client.v1.data.replication.model.ConsistencyLevel;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Builder;

/**
 * Represents the <a href="https://weaviate.io/">Weaviate</a> vector database.
 * Current implementation assumes the cosine distance metric is used.
 */
public class WeaviateEmbeddingStore implements EmbeddingStore<TextSegment> {

  private static final String DEFAULT_CLASS = "Default";
  private static final boolean DEFAULT_AVOID_DUPS = true;
  private static final String DEFAULT_CONSISTENCY_LEVEL = ConsistencyLevel.QUORUM;
  private static final String METADATA_TEXT_SEGMENT = "text";
  private static final String ADDITIONALS = "_additional";

  private final WeaviateClient client;
  private final String objectClass;
  private boolean avoidDups;
  private String consistencyLevel;

  /**
   * Creates a new WeaviateEmbeddingStore instance.
   *
   * @param apiKey           your Weaviate API key
   * @param scheme           the scheme, e.g. "https" of cluster URL. Find in under Details of your Weaviate cluster.
   * @param host             the host, e.g. "langchain4j-4jw7ufd9.weaviate.network" of cluster URL.
   *                         Find in under Details of your Weaviate cluster.
   * @param objectClass      the object class you want to store, e.g. "MyGreatClass"
   * @param avoidDups        if true (default), then <code>WeaviateEmbeddingStore</code> will generate a hashed ID based on
   *                         provided text segment, which avoids duplicated entries in DB.
   *                         If false, then random ID will be generated.
   * @param consistencyLevel Consistency level: ONE, QUORUM (default) or ALL. Find more details <a href="https://weaviate.io/developers/weaviate/concepts/replication-architecture/consistency#tunable-write-consistency">here</a>.
   */
  @Builder
  public WeaviateEmbeddingStore(
    String apiKey,
    String scheme,
    String host,
    String objectClass,
    Boolean avoidDups,
    String consistencyLevel
  ) {
    try {
      client = WeaviateAuthClient.apiKey(new Config(scheme, host), apiKey);
    } catch (AuthException e) {
      throw new IllegalArgumentException(e);
    }
    this.objectClass = objectClass != null ? objectClass : DEFAULT_CLASS;
    this.avoidDups = avoidDups != null ? avoidDups : DEFAULT_AVOID_DUPS;
    this.consistencyLevel = consistencyLevel != null ? consistencyLevel : DEFAULT_CONSISTENCY_LEVEL;
  }

  @Override
  public String add(Embedding embedding) {
    String id = randomUUID();
    add(id, embedding);
    return id;
  }

  /**
   * Adds a new embedding with provided ID to the store.
   *
   * @param id        the ID of the embedding to add in UUID format, since it's Weaviate requirement.
   *                  See <a href="https://weaviate.io/developers/weaviate/manage-data/create#id">Weaviate docs</a> and
   *                  <a href="https://en.wikipedia.org/wiki/Universally_unique_identifier">UUID on Wikipedia</a>
   * @param embedding the embedding to add
   */
  @Override
  public void add(String id, Embedding embedding) {
    addAll(singletonList(id), singletonList(embedding), null);
  }

  @Override
  public String add(Embedding embedding, TextSegment textSegment) {
    return addAll(singletonList(embedding), singletonList(textSegment)).stream().findFirst().orElse(null);
  }

  @Override
  public List<String> addAll(List<Embedding> embeddings) {
    return addAll(embeddings, null);
  }

  @Override
  public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
    return addAll(null, embeddings, embedded);
  }

  /**
   * {@inheritDoc}
   * The score inside {@link EmbeddingMatch} is Weaviate's certainty.
   */
  @Override
  public List<EmbeddingMatch<TextSegment>> findRelevant(
    Embedding referenceEmbedding,
    int maxResults,
    double minCertainty
  ) {
    Result<GraphQLResponse> result = client
      .graphQL()
      .get()
      .withClassName(objectClass)
      .withFields(
        Field.builder().name(METADATA_TEXT_SEGMENT).build(),
        Field
          .builder()
          .name(ADDITIONALS)
          .fields(
            Field.builder().name("id").build(),
            Field.builder().name("certainty").build(),
            Field.builder().name("vector").build()
          )
          .build()
      )
      .withNearVector(
        NearVectorArgument
          .builder()
          .vector(referenceEmbedding.vectorAsList().toArray(new Float[0]))
          .certainty((float) minCertainty)
          .build()
      )
      .withLimit(maxResults)
      .run();

    if (result.hasErrors()) {
      throw new IllegalArgumentException(
        result.getError().getMessages().stream().map(WeaviateErrorMessage::getMessage).collect(joining("\n"))
      );
    }

    Optional<Map.Entry<String, Map>> resGetPart =
      ((Map<String, Map>) result.getResult().getData()).entrySet().stream().findFirst();
    if (!resGetPart.isPresent()) {
      return emptyList();
    }

    Optional resItemsPart = resGetPart.get().getValue().entrySet().stream().findFirst();
    if (!resItemsPart.isPresent()) {
      return emptyList();
    }

    List<Map<String, ?>> resItems = ((Map.Entry<String, List<Map<String, ?>>>) resItemsPart.get()).getValue();

    return resItems.stream().map(WeaviateEmbeddingStore::toEmbeddingMatch).collect(Collectors.toList());
  }

  private List<String> addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
    if (embedded != null && embeddings.size() != embedded.size()) {
      throw new IllegalArgumentException("The list of embeddings and embedded must have the same size");
    }

    List<String> resIds = new ArrayList<>();
    List<WeaviateObject> objects = new ArrayList<>();
    for (int i = 0; i < embeddings.size(); i++) {
      String id = ids != null
        ? ids.get(i)
        : avoidDups && embedded != null ? generateUUIDFrom(embedded.get(i).text()) : randomUUID();
      resIds.add(id);
      objects.add(buildObject(id, embeddings.get(i), embedded != null ? embedded.get(i).text() : null));
    }

    client
      .batch()
      .objectsBatcher()
      .withObjects(objects.toArray(new WeaviateObject[0]))
      .withConsistencyLevel(consistencyLevel)
      .run();

    return resIds;
  }

  private WeaviateObject buildObject(String id, Embedding embedding, String text) {
    WeaviateObject.WeaviateObjectBuilder builder = WeaviateObject
      .builder()
      .className(objectClass)
      .id(id)
      .vector(embedding.vectorAsList().toArray(new Float[0]));

    if (text != null) {
      Map<String, Object> props = new HashMap<>();
      props.put(METADATA_TEXT_SEGMENT, text);

      builder.properties(props);
    }

    return builder.build();
  }

  private static EmbeddingMatch<TextSegment> toEmbeddingMatch(Map<String, ?> item) {
    Map<String, ?> additional = (Map<String, ?>) item.get(ADDITIONALS);

    return new EmbeddingMatch<>(
      (Double) additional.get("certainty"),
      (String) additional.get("id"),
      Embedding.from(
        ((List<Double>) additional.get("vector")).stream().map(Double::floatValue).collect(Collectors.toList())
      ),
      TextSegment.from((String) item.get(METADATA_TEXT_SEGMENT))
    );
  }
}
