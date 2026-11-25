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
    val target_index: String? = null,
    /**
     * Marks the relation as carrying edge properties. When set, a `_relations` row is
     * materialised and the optional `<relation>_properties` object on the record is stored
     * in the row's `properties` column.
     */
    val has_properties: Boolean = false
)

@Serializable
data class CollectionPolicies(
    val read: String = "public",
    val create: String = "public",
    val update: String = "admin",
    val delete: String = "admin"
)


/**
 * Declarative roll-up of a child collection into a header row. The engine computes the
 * values; cards only bind them, so money is never arithmetic on the client.
 *
 * `outputs` maps a header field to a tiny, whitelisted expression:
 * `count()`, `sum(<field>)`, `sum(<field> * <field>)`, `round2(<expr>)`.
 */
@Serializable
data class AggregateRule(
    val target: String,
    val foreign_field: String,
    val outputs: Map<String, String> = emptyMap(),
    /** Refuse the commit when the header has no children. */
    val require_non_empty: Boolean = false,
    /** Header status written on a successful commit, e.g. `submitted`. */
    val commit_status: String? = null,
    /** Only commit headers whose status is one of these (empty = no restriction). */
    val editable_status: List<String> = emptyList()
)

/**
 * Deterministic serialisation view: which header fields and child rows form the payload,
 * in what order, and which header fields store the packed transfer.
 */
@Serializable
data class CanonicalView(
    val fields: List<String>,
    val child: String? = null,
    /** Child field that holds the parent id. */
    val child_foreign_field: String? = null,
    val child_fields: List<String> = emptyList(),
    val version: Int = 1,
    val chunk_bytes: Int = 500,
    /** Result key -> header field. Keys: payload, checksum, chunks, chunk_bytes, chunk_count. */
    val store: Map<String, String> = emptyMap()
)

/** Field (and optional fallback field) that a scanned value is resolved against. */
@Serializable
data class LookupRule(
    val field: String,
    val secondary_field: String? = null
)

/** Inbound reference that can block a delete; declared on the referenced collection. */
@Serializable
data class ReferenceRule(
    val collection: String,
    val field: String,
    val match: String = "id",
    val restrict_delete: Boolean = true,
    val message: String? = null
)


/** Stock ceiling checked when a child quantity is written. */
@Serializable
data class StockGuard(
    val collection: String,
    val match_field: String,
    val stock_field: String = "stock"
)

/**
 * Declares that this collection is a child list of a header: how to merge a repeated
 * entry, which field carries the quantity, and where the stock ceiling lives. Generic —
 * the engine never learns what a "line" or a "cart" is.
 */
@Serializable
data class ChildAppendRule(
    val parent: String,
    /** Field on this child that holds the parent id. */
    val parent_field: String,
    /** Merge two rows when this field matches; the quantity is summed. */
    val merge_key: String,
    val quantity_field: String = "quantity",
    val stock: StockGuard? = null
)

@Serializable
data class CollectionSchema(
    val fields: Map<String, FieldDefinition> = emptyMap(),
    val relations: Map<String, RelationDefinition> = emptyMap(),
    val policies: CollectionPolicies = CollectionPolicies(),
    val composite_unique: List<List<String>> = emptyList(),
    val field_history: JsonObject = JsonObject(emptyMap()),
    val aggregates: List<AggregateRule> = emptyList(),
    val canonical: CanonicalView? = null,
    val lookup: LookupRule? = null,
    val references: List<ReferenceRule> = emptyList(),
    val append: ChildAppendRule? = null
)

@Serializable
data class Collection(
    val id: Long,
    val name: String,
    val schema: CollectionSchema? = null,
    val index: String? = null,
    /** Rows inserted the first time this collection is created, so a workspace ships with data. */
    val seed: List<JsonObject> = emptyList()
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
    val id: String,
    val collectionId: Long,
    val data: JsonObject,
    val created: String,
    val updated: String
)