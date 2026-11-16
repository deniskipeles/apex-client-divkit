package engine.db.models

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
enum class FieldType {
    @SerialName("string") STRING,
    @SerialName("text") TEXT,
    @SerialName("number") NUMBER,
    @SerialName("boolean") BOOLEAN,
    @SerialName("email") EMAIL,
    @SerialName("url") URL,
    @SerialName("date") DATE,
    @SerialName("select") SELECT,
    @SerialName("json") JSON,
    @SerialName("file") FILE,
    @SerialName("blob") BLOB,
    @SerialName("relation") RELATION,
    @SerialName("owner") OWNER,
    @SerialName("vector") VECTOR,
    @SerialName("geopoint") GEOPOINT
}

@Serializable
enum class RelationType {
    @SerialName("one") ONE,
    @SerialName("many") MANY
}

@Serializable
data class FieldDefinition(
    val type: FieldType,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val ose_indexed: Boolean = false,
    val sql_indexed: Boolean = false,
    val vectorize: Boolean = false,
    val auto: Boolean = false,
    val position: Int = 0,
    val uid: String,
    val unique: Boolean? = null,
    val min: Double? = null,
    val max: Double? = null,
    val min_length: Int? = null,
    val max_length: Int? = null,
    val pattern: String? = null,
    val options: List<String>? = null,
    val relation_to: String? = null
)

@Serializable
data class RelationDefinition(
    val target_collection: String,
    val relation_type: RelationType,
    val position: Int = 0,
    val uid: String,
    val required: Boolean = false,
    val sql_indexed: Boolean = false,
    val ose_indexed: Boolean = false,
    val cascade_on_target_delete: Boolean = false,
    val target_index: String? = null
)

@Serializable
data class CollectionPolicies(
    val read: String = "public",
    val create: String = "public",
    val update: String = "admin",
    val delete: String = "admin"
)

@Serializable
data class CollectionSchema(
    val fields: Map<String, FieldDefinition> = emptyMap(),
    val relations: Map<String, RelationDefinition> = emptyMap(),
    val policies: CollectionPolicies = CollectionPolicies(),
    val composite_unique: List<List<String>> = emptyList(),
    val field_history: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class Collection(
    val id: Long,
    val name: String,
    val schema: CollectionSchema? = null,
    val index: String? = null
)

@Serializable
data class TenantSchemaPackage(
    val apexkit_version: String = "0.1.0",
    val version: String = "0.1.0",
    val strategy: String = "skip",
    val exported_at: String? = null,
    val collections: List<Collection> = emptyList()
)

@Serializable
data class Record(
    val id: Long,
    val collectionId: Long,
    val data: JsonObject,
    val created: String,
    val updated: String
)