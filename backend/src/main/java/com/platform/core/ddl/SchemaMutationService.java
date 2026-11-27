package com.platform.core.ddl;

import com.platform.core.ddl.DdlGenerator.ColumnSpec;
import com.platform.core.ddl.SchemaChangeRequests.ChoiceRequest;
import com.platform.core.ddl.SchemaChangeRequests.CreateTableRequest;
import com.platform.core.ddl.SchemaChangeRequests.FieldRequest;
import com.platform.core.error.BadRequestException;
import com.platform.core.error.ConflictException;
import com.platform.core.error.ForbiddenException;
import com.platform.core.error.NotFoundException;
import com.platform.core.metadata.BaseColumns;
import com.platform.core.metadata.DictionaryService;
import com.platform.core.metadata.FieldDefinition;
import com.platform.core.metadata.FieldType;
import com.platform.core.metadata.SchemaCache;
import com.platform.core.metadata.TableDefinition;
import com.platform.core.security.UserContext;
import com.platform.core.tenant.TenantContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one place schema changes happen. Each mutation runs the metadata
 * inserts and the physical DDL in a single transaction — Postgres has
 * transactional DDL, so a failure anywhere rolls back both and the metadata
 * can never disagree with the physical schema. That property is the reason
 * this platform targets Postgres specifically.
 *
 * Mutations are serialised with a transaction-scoped advisory lock so two
 * concurrent create-table calls cannot interleave their existence checks and
 * DDL. The lock is cheap and only taken on this (rare, administrative) path.
 *
 * The schema cache is reloaded only after the transaction commits — a
 * TransactionTemplate rather than @Transactional makes that ordering explicit
 * instead of relying on proxy self-invocation subtleties.
 */
@Service
public class SchemaMutationService {

    /** Arbitrary but fixed: all schema mutations contend on this one key. */
    static final long SCHEMA_LOCK_KEY = 0x5EED_CAFEL;

    private final NamedParameterJdbcTemplate jdbc;
    private final DictionaryService dictionary;
    private final SchemaCache schemaCache;
    private final TenantContext tenant;
    private final TransactionTemplate tx;

    public SchemaMutationService(NamedParameterJdbcTemplate jdbc,
                                 DictionaryService dictionary,
                                 SchemaCache schemaCache,
                                 TenantContext tenant,
                                 PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.dictionary = dictionary;
        this.schemaCache = schemaCache;
        this.tenant = tenant;
        this.tx = new TransactionTemplate(txManager);
    }

    public TableDefinition createTable(CreateTableRequest req, UserContext user) {
        requireAdmin(user);
        String name = IdentifierValidator.validate(req.name());
        String label = req.label() != null && !req.label().isBlank() ? req.label() : name;
        List<FieldRequest> fields = req.fields() != null ? req.fields() : List.of();

        Set<String> seen = new HashSet<>();
        for (FieldRequest f : fields) {
            IdentifierValidator.validate(f.element());
            if (!seen.add(f.element())) {
                throw new BadRequestException("Duplicate field '" + f.element() + "' in request");
            }
        }

        tx.executeWithoutResult(status -> {
            acquireSchemaLock();
            if (dictionary.findTableByName(name).isPresent()) {
                throw new ConflictException("Table '" + name + "' already exists");
            }

            TableDefinition parent = null;
            if (req.extendsTable() != null && !req.extendsTable().isBlank()) {
                parent = dictionary.findTableByName(req.extendsTable())
                        .orElseThrow(() -> new NotFoundException(
                                "Parent table '" + req.extendsTable() + "' does not exist"));
                if (!parent.extendable()) {
                    throw new BadRequestException(
                            "Table '" + parent.name() + "' is not extendable");
                }
                for (FieldRequest f : fields) {
                    rejectIfShadowsChain(parent, f.element());
                }
            }

            boolean extendable = Boolean.TRUE.equals(req.extendable());
            UUID tableId = dictionary.insertTable(name, label,
                    parent != null ? parent.sysId() : null, extendable, user.username());

            List<ColumnSpec> columns = new ArrayList<>();
            for (FieldRequest f : fields) {
                columns.add(insertFieldMetadata(tableId, f, user));
            }

            String ddl = parent == null
                    ? DdlGenerator.createRootTable(tenant.schema(), name, columns)
                    : DdlGenerator.createChildTable(tenant.schema(), name, parent.name(), columns);
            jdbc.getJdbcTemplate().execute(ddl);
            dictionary.bumpSchemaVersion();
        });

        schemaCache.reload();
        return schemaCache.require(name);
    }

    public FieldDefinition addField(String tableName, FieldRequest req, UserContext user) {
        requireAdmin(user);
        String element = IdentifierValidator.validate(req.element());

        tx.executeWithoutResult(status -> {
            acquireSchemaLock();
            TableDefinition table = dictionary.findTableByName(tableName)
                    .orElseThrow(() -> new NotFoundException("Unknown table: " + tableName));
            rejectIfShadowsChain(table, element);
            // Also reject fields declared on descendants: a parent field added
            // later must not collide with an existing child column.
            rejectIfDeclaredBelow(table, element);

            ColumnSpec spec = insertFieldMetadata(table.sysId(), req, user);
            jdbc.getJdbcTemplate().execute(
                    DdlGenerator.addColumn(tenant.schema(), table.name(), spec));
            dictionary.bumpSchemaVersion();
        });

        schemaCache.reload();
        TableDefinition table = schemaCache.require(tableName);
        return schemaCache.field(table, element).orElseThrow();
    }

    public void addChoice(String tableName, String element, ChoiceRequest choice, UserContext user) {
        requireAdmin(user);
        tx.executeWithoutResult(status -> {
            acquireSchemaLock();
            TableDefinition table = dictionary.findTableByName(tableName)
                    .orElseThrow(() -> new NotFoundException("Unknown table: " + tableName));
            FieldDefinition field = schemaCache.field(table, element)
                    .orElseThrow(() -> new NotFoundException(
                            "Unknown field '" + element + "' on table '" + tableName + "'"));
            if (field.type() != FieldType.CHOICE) {
                throw new BadRequestException("Field '" + element + "' is not a choice field");
            }
            dictionary.insertChoice(field.tableId(), element, choice.value(), choice.label(),
                    choice.sequence() != null ? choice.sequence() : 0, user.username());
            dictionary.bumpSchemaVersion();
        });
        schemaCache.reload();
    }

    /**
     * Validates one field request, writes its sys_dictionary (and sys_choice)
     * rows, and returns the ColumnSpec for DDL generation. Caller owns the
     * transaction.
     */
    private ColumnSpec insertFieldMetadata(UUID tableId, FieldRequest f, UserContext user) {
        String element = IdentifierValidator.validate(f.element());
        FieldType type = FieldType.fromInternal(f.type());
        String label = f.label() != null && !f.label().isBlank() ? f.label() : element;

        if (f.maxLength() != null && type != FieldType.STRING) {
            throw new BadRequestException("maxLength is only valid for string fields ('" + element + "')");
        }
        if (f.maxLength() != null && (f.maxLength() < 1 || f.maxLength() > 4000)) {
            throw new BadRequestException("maxLength must be between 1 and 4000 ('" + element + "')");
        }

        String referenceTableName = null;
        UUID referenceTableId = null;
        if (type == FieldType.REFERENCE) {
            if (f.referenceTable() == null || f.referenceTable().isBlank()) {
                throw new BadRequestException(
                        "Field '" + element + "' is a reference and requires referenceTable");
            }
            TableDefinition target = dictionary.findTableByName(f.referenceTable())
                    .orElseThrow(() -> new NotFoundException(
                            "Referenced table '" + f.referenceTable() + "' does not exist"));
            referenceTableName = target.name();
            referenceTableId = target.sysId();
        } else if (f.referenceTable() != null) {
            throw new BadRequestException(
                    "referenceTable is only valid for reference fields ('" + element + "')");
        }

        if (f.choices() != null && !f.choices().isEmpty() && type != FieldType.CHOICE) {
            throw new BadRequestException("choices are only valid for choice fields ('" + element + "')");
        }

        dictionary.insertField(tableId, element, label, type, f.maxLength(), referenceTableId,
                Boolean.TRUE.equals(f.mandatory()), f.defaultValue(),
                Boolean.TRUE.equals(f.display()), user.username());

        if (f.choices() != null) {
            int seq = 0;
            for (ChoiceRequest c : f.choices()) {
                if (c.value() == null || c.value().isBlank()) {
                    throw new BadRequestException("Choice value must not be empty ('" + element + "')");
                }
                dictionary.insertChoice(tableId, element, c.value(),
                        c.label() != null ? c.label() : c.value(),
                        c.sequence() != null ? c.sequence() : seq, user.username());
                seq++;
            }
        }

        return new ColumnSpec(element, type, f.maxLength(), referenceTableName);
    }

    /**
     * pg_advisory_xact_lock blocks until available and releases automatically
     * at commit/rollback, so there is no unlock path to get wrong.
     */
    private void acquireSchemaLock() {
        jdbc.query("select pg_advisory_xact_lock(:key)", Map.of("key", SCHEMA_LOCK_KEY), rs -> null);
    }

    private void rejectIfShadowsChain(TableDefinition table, String element) {
        if (BaseColumns.isBase(element)) {
            throw new BadRequestException("Field '" + element + "' collides with a platform base column");
        }
        Optional<FieldDefinition> existing = schemaCache.table(table.name())
                .flatMap(t -> schemaCache.field(t, element));
        if (existing.isPresent()) {
            throw new ConflictException(
                    "Field '" + element + "' already exists on '" + table.name() + "' or an ancestor");
        }
    }

    private void rejectIfDeclaredBelow(TableDefinition table, String element) {
        for (TableDefinition child : schemaCache.childrenOf(table)) {
            if (child.ownField(element).isPresent()) {
                throw new ConflictException(
                        "Field '" + element + "' already exists on child table '" + child.name() + "'");
            }
            rejectIfDeclaredBelow(child, element);
        }
    }

    private void requireAdmin(UserContext user) {
        if (!user.isAdmin()) {
            throw new ForbiddenException("Schema changes require the admin role");
        }
    }
}
