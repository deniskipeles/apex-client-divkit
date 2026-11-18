import Foundation

/// Schema model mirroring `engine.db.models.*` (ApexKitSchema.kt).

public enum FieldType: String, Sendable {
    case string, text, number, boolean, email, url, date, select, json, file, blob, relation, owner, vector, geopoint
}

public enum RelationType: String, Sendable {
    case one, many
}

public struct FieldDefinition: Sendable {
    public var type: FieldType
    public var required: Bool
    public var unique: Bool?
    public var relationTo: String?
    public var uid: String
    public var sqlIndexed: Bool

    public init(type: FieldType, required: Bool = false, unique: Bool? = nil, relationTo: String? = nil, uid: String = "", sqlIndexed: Bool = false) {
        self.type = type
        self.required = required
        self.unique = unique
        self.relationTo = relationTo
        self.uid = uid
        self.sqlIndexed = sqlIndexed
    }
}

public struct RelationDefinition: Sendable {
    public var targetCollection: String
    public var relationType: RelationType
    public var required: Bool
    public var uid: String
    public var cascadeOnTargetDelete: Bool
    public var sqlIndexed: Bool
    /// Marks the relation as carrying edge properties. When set, a `_relations` row is
    /// materialised and the optional `<relation>_properties` object is stored in `properties`.
    public var hasProperties: Bool

    public init(
        targetCollection: String,
        relationType: RelationType,
        required: Bool = false,
        uid: String = "",
        cascadeOnTargetDelete: Bool = false,
        sqlIndexed: Bool = false,
        hasProperties: Bool = false
    ) {
        self.targetCollection = targetCollection
        self.relationType = relationType
        self.required = required
        self.uid = uid
        self.cascadeOnTargetDelete = cascadeOnTargetDelete
        self.sqlIndexed = sqlIndexed
        self.hasProperties = hasProperties
    }
}

public struct CollectionPolicies: Sendable {
    public var read: String
    public var create: String
    public var update: String
    public var delete: String

    public init(read: String = "public", create: String = "public", update: String = "admin", delete: String = "admin") {
        self.read = read
        self.create = create
        self.update = update
        self.delete = delete
    }
}

public struct CollectionSchema: Sendable {
    public var fields: [String: FieldDefinition]
    public var relations: [String: RelationDefinition]
    public var policies: CollectionPolicies
    public var compositeUnique: [[String]]

    public init(
        fields: [String: FieldDefinition] = [:],
        relations: [String: RelationDefinition] = [:],
        policies: CollectionPolicies = CollectionPolicies(),
        compositeUnique: [[String]] = []
    ) {
        self.fields = fields
        self.relations = relations
        self.policies = policies
        self.compositeUnique = compositeUnique
    }

    /// Decodes the `schema` JSON column written by `importTenantSchema`.
    public static func decode(_ json: JsonValue?) -> CollectionSchema {
        guard let obj = json, case let .object(pairs) = obj else { return CollectionSchema() }
        var fields: [String: FieldDefinition] = [:]
        var relations: [String: RelationDefinition] = [:]
        var policies = CollectionPolicies()
        var composite: [[String]] = []

        for (key, value) in pairs {
            switch key {
            case "fields":
                for (name, def) in value.asDictionary {
                    let type = FieldType(rawValue: def["type"]?.stringOrNull ?? "string") ?? .string
                    fields[name] = FieldDefinition(
                        type: type,
                        required: def["required"]?.boolOrNull ?? false,
                        unique: def["unique"]?.boolOrNull,
                        relationTo: def["relation_to"]?.stringOrNull,
                        uid: def["uid"]?.stringOrNull ?? "",
                        sqlIndexed: def["sql_indexed"]?.boolOrNull ?? false
                    )
                }
            case "relations":
                for (name, def) in value.asDictionary {
                    let rt = RelationType(rawValue: def["relation_type"]?.stringOrNull ?? "one") ?? .one
                    relations[name] = RelationDefinition(
                        targetCollection: def["target_collection"]?.stringOrNull ?? "",
                        relationType: rt,
                        required: def["required"]?.boolOrNull ?? false,
                        uid: def["uid"]?.stringOrNull ?? "",
                        cascadeOnTargetDelete: def["cascade_on_target_delete"]?.boolOrNull ?? false,
                        hasProperties: def["has_properties"]?.boolOrNull ?? false
                    )
                }
            case "policies":
                policies = CollectionPolicies(
                    read: value["read"]?.stringOrNull ?? "public",
                    create: value["create"]?.stringOrNull ?? "public",
                    update: value["update"]?.stringOrNull ?? "admin",
                    delete: value["delete"]?.stringOrNull ?? "admin"
                )
            case "composite_unique":
                composite = (value.arrayValue ?? []).map { ($0.arrayValue ?? []).compactMap { $0.stringOrNull } }
            default:
                break
            }
        }
        return CollectionSchema(fields: fields, relations: relations, policies: policies, compositeUnique: composite)
    }
}

public struct Collection: Sendable {
    public let id: Int64
    public let name: String
    public let schema: CollectionSchema?
    public let index: String?

    public init(id: Int64, name: String, schema: CollectionSchema? = nil, index: String? = nil) {
        self.id = id
        self.name = name
        self.schema = schema
        self.index = index
    }
}

public struct TenantSchemaPackage: Sendable {
    public var collections: [Collection]

    public init(collections: [Collection] = []) {
        self.collections = collections
    }

    /// Decodes a schema package from JSON (mirrors `importTenantSchemaJson`).
    public static func decode(_ json: JsonValue) -> TenantSchemaPackage {
        let cols = (json["collections"]?.arrayValue ?? []).map { item -> Collection in
            Collection(
                id: item["id"]?.intOrNull ?? 0,
                name: item["name"]?.stringOrNull ?? "",
                schema: item["schema"].map { CollectionSchema.decode($0) },
                index: item["index"]?.stringOrNull
            )
        }
        return TenantSchemaPackage(collections: cols)
    }
}

public struct Record: Sendable {
    public let id: String
    public let collectionId: Int64
    public let data: JsonValue
    public let created: String
    public let updated: String

    public init(id: String, collectionId: Int64, data: JsonValue, created: String, updated: String) {
        self.id = id
        self.collectionId = collectionId
        self.data = data
        self.created = created
        self.updated = updated
    }
}
