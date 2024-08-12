package io.apicurio.registry.storage.importing.v2;

import io.apicurio.registry.content.ContentHandle;
import io.apicurio.registry.content.TypedContent;
import io.apicurio.registry.model.GAV;
import io.apicurio.registry.storage.RegistryStorage;
import io.apicurio.registry.storage.dto.ArtifactReferenceDto;
import io.apicurio.registry.storage.dto.EditableArtifactMetaDataDto;
import io.apicurio.registry.storage.error.InvalidArtifactTypeException;
import io.apicurio.registry.storage.error.VersionAlreadyExistsException;
import io.apicurio.registry.storage.impexp.EntityInputStream;
import io.apicurio.registry.storage.impl.sql.RegistryStorageContentUtils;
import io.apicurio.registry.storage.impl.sql.SqlUtil;
import io.apicurio.registry.types.ContentTypes;
import io.apicurio.registry.types.RegistryException;
import io.apicurio.registry.types.VersionState;
import io.apicurio.registry.utils.impexp.Entity;
import io.apicurio.registry.utils.impexp.v2.ArtifactRuleEntity;
import io.apicurio.registry.utils.impexp.v2.ArtifactVersionEntity;
import io.apicurio.registry.utils.impexp.v2.CommentEntity;
import io.apicurio.registry.utils.impexp.v2.ContentEntity;
import io.apicurio.registry.utils.impexp.v2.GlobalRuleEntity;
import io.apicurio.registry.utils.impexp.v2.GroupEntity;
import io.apicurio.registry.utils.impexp.v3.ArtifactEntity;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.apicurio.registry.types.ArtifactType.*;

/**
 * This class takes a stream of Registry v2 entities and imports them into the application using
 * {@link SqlDataUpgrader#importData(EntityInputStream, Runnable)} as it's entry point. It must be used in the
 * upgrade process from v2 to v3.
 */
public class SqlDataUpgrader extends AbstractDataImporter {

    protected RegistryStorageContentUtils utils;

    protected final RegistryStorage storage;

    protected final boolean preserveGlobalId;

    protected final boolean preserveContentId;

    // To handle the case where we are trying to import a version before its content has been imported
    protected final List<ArtifactVersionEntity> waitingForContent = new ArrayList<>();

    // To handle the case where we are trying to import a comment before its version has been imported
    private final List<CommentEntity> waitingForVersion = new ArrayList<>();

    // ID remapping
    protected final Map<Long, Long> globalIdMapping = new HashMap<>();
    protected final Map<Long, Long> contentIdMapping = new HashMap<>();

    // Collection of content waiting for required references. A given content cannot be imported unless the
    // expected reference is present.
    // TODO do a second round to this, since this currently means enforcing the integrity rule. (Maybe try a
    // first round and, if there are no artifacts remaining, just import the orphaned content).
    protected final Map<ContentEntity, Set<GAV>> waitingForReference = new HashMap<>();

    // To keep track of which versions have been imported
    private final Set<GAV> gavDone = new HashSet<>();

    public SqlDataUpgrader(Logger logger, RegistryStorageContentUtils utils, RegistryStorage storage,
            boolean preserveGlobalId, boolean preserveContentId) {
        super(logger);
        this.utils = utils;
        this.storage = storage;
        this.preserveGlobalId = preserveGlobalId;
        this.preserveContentId = preserveContentId;
    }

    @Override
    protected void importArtifactRule(ArtifactRuleEntity entity) {
        try {
            io.apicurio.registry.utils.impexp.v3.ArtifactRuleEntity newEntity = io.apicurio.registry.utils.impexp.v3.ArtifactRuleEntity
                    .builder().type(entity.type).artifactId(entity.artifactId)
                    .configuration(entity.configuration).groupId(entity.groupId).build();
            storage.importArtifactRule(newEntity);
            log.debug("Artifact rule imported successfully: {}", entity);
        } catch (Exception ex) {
            log.warn("Failed to import artifact rule {}: {}", entity, ex.getMessage());
        }
    }

    @Override
    public void importArtifactVersion(ArtifactVersionEntity entity) {
        try {
            // Content needs to be imported before artifact version
            if (!contentIdMapping.containsKey(entity.contentId)) {
                // Add to the queue waiting for content imported
                waitingForContent.add(entity);
                return;
            }

            entity.contentId = contentIdMapping.get(entity.contentId);

            var oldGlobalId = entity.globalId;
            if (!preserveGlobalId) {
                entity.globalId = storage.nextGlobalId();
            }

            Map<String, String> artifactVersionLabels = new HashMap<>();

            if (entity.labels != null) {
                artifactVersionLabels
                        .putAll(entity.labels.stream().collect(Collectors.toMap(label -> label, s -> null)));
            }

            if (entity.properties != null) {
                artifactVersionLabels.putAll(entity.properties);
            }

            io.apicurio.registry.utils.impexp.v3.ArtifactVersionEntity newEntity = io.apicurio.registry.utils.impexp.v3.ArtifactVersionEntity
                    .builder().createdOn(entity.createdOn).description(entity.description)
                    .labels(entity.labels != null
                        ? entity.labels.stream().collect(Collectors.toMap(label -> label, label -> null))
                        : Collections.emptyMap())
                    .name(entity.name).owner(entity.createdBy)
                    .state(VersionState.fromValue(entity.state.value())).artifactId(entity.artifactId)
                    .versionOrder(entity.versionId).modifiedOn(entity.createdOn).modifiedBy(entity.createdBy)
                    .version(entity.version).globalId(entity.globalId).contentId(entity.contentId)
                    .groupId(entity.groupId).build();

            // If the version being imported is the first one, we have to create the artifact first
            if (!storage.isArtifactExists(entity.groupId, entity.artifactId)) {
                ArtifactEntity artifactEntity = ArtifactEntity.builder().artifactId(entity.artifactId)
                        .artifactType(entity.artifactType).createdOn(entity.createdOn)
                        .description(entity.description).groupId(entity.groupId).labels(artifactVersionLabels)
                        .modifiedBy(entity.createdBy).modifiedOn(entity.createdOn).name(entity.name)
                        .owner(entity.createdBy).build();
                storage.importArtifact(artifactEntity);
            }

            if (entity.isLatest) {
                // If this version is the latest, update the artifact metadata with its metadata
                EditableArtifactMetaDataDto editableArtifactMetaDataDto = EditableArtifactMetaDataDto
                        .builder().name(newEntity.name).owner(newEntity.owner)
                        .description(newEntity.description).labels(newEntity.labels).build();

                storage.updateArtifactMetaData(newEntity.groupId, newEntity.artifactId,
                        editableArtifactMetaDataDto);
            }

            storage.importArtifactVersion(newEntity);
            log.debug("Artifact version imported successfully: {}", entity);
            globalIdMapping.put(oldGlobalId, entity.globalId);
            var gav = new GAV(entity.groupId, entity.artifactId, entity.version);
            gavDone.add(gav);

            // Import comments that were waiting for this version
            var commentsToImport = waitingForVersion.stream()
                    .filter(comment -> comment.globalId == oldGlobalId).toList();
            for (CommentEntity commentEntity : commentsToImport) {
                importComment(commentEntity);
            }
            waitingForVersion.removeAll(commentsToImport);

            // Once the artifact version is processed, check if there is some content waiting for this as it's
            // reference
            // For each content waiting for the version we just inserted, remove it from the list.
            waitingForReference.values().forEach(waitingReferences -> waitingReferences.remove(gav));

            // Finally, once the list of required deps is updated, if it was the last reference needed, import
            // the content.
            waitingForReference.keySet().stream()
                    .filter(content -> waitingForReference.get(content).isEmpty())
                    .forEach(contentToImport -> {
                        if (!contentIdMapping.containsKey(contentToImport.contentId)) {
                            importContent(contentToImport);
                        }
                    });
        } catch (VersionAlreadyExistsException ex) {
            if (ex.getGlobalId() != null) {
                log.warn("Duplicate globalId {} detected, skipping import of artifact version: {}",
                        ex.getGlobalId(), entity);
            } else {
                log.warn("Failed to import artifact version {}: {}", entity, ex.getMessage());
            }
        } catch (Exception ex) {
            log.warn("Failed to import artifact version {}: {}", entity, ex.getMessage());
        }
    }

    @Override
    public void importContent(ContentEntity entity) {
        try {
            List<ArtifactReferenceDto> references = SqlUtil
                    .deserializeReferences(entity.serializedReferences);

            Set<GAV> referencesGavs = references
                    .stream().map(referenceDto -> new GAV(referenceDto.getGroupId(),
                            referenceDto.getArtifactId(), referenceDto.getVersion()))
                    .collect(Collectors.toSet());

            Set<ArtifactReferenceDto> requiredReferences = new HashSet<>();

            // If there are references and they've not been imported yet, add them to the waiting collection
            if (!references.isEmpty() && !gavDone.containsAll(referencesGavs)) {
                waitingForReference.put(entity, referencesGavs);

                // For each artifact reference, if it has not been imported yet, add it to the waiting list
                // for this content.
                referencesGavs.stream()
                        .filter(artifactReference -> !referencesGavs.contains(artifactReference))
                        .forEach(artifactReference -> waitingForReference.get(entity).add(artifactReference));

                // This content cannot be imported until all the references are imported.
                return;
            }

            TypedContent typedContent = TypedContent.create(ContentHandle.create(entity.contentBytes), null);
            Map<String, TypedContent> resolvedReferences = storage.resolveReferences(references);
            entity.artifactType = utils.determineArtifactType(typedContent, null, resolvedReferences);

            // First we have to recalculate both the canonical hash and the contentHash
            TypedContent canonicalContent = utils.canonicalizeContent(entity.artifactType, typedContent,
                    resolvedReferences);

            entity.canonicalHash = DigestUtils.sha256Hex(canonicalContent.getContent().bytes());
            entity.contentHash = utils.getContentHash(typedContent, references);

            // Then, based on the configuration, a new id is requested or the old one is used.
            var oldContentId = entity.contentId;
            if (!preserveContentId) {
                entity.contentId = storage.nextContentId();
            }

            // Finally, using the information from the old content, a V3 content entity is created.
            io.apicurio.registry.utils.impexp.v3.ContentEntity newEntity = io.apicurio.registry.utils.impexp.v3.ContentEntity
                    .builder().contentType(determineContentType(entity.artifactType, typedContent))
                    .contentHash(entity.contentHash).artifactType(entity.artifactType)
                    .contentBytes(entity.contentBytes).serializedReferences(entity.serializedReferences)
                    .canonicalHash(entity.canonicalHash).contentId(entity.contentId).build();

            storage.importContent(newEntity);
            log.debug("Content imported successfully: {}", entity);

            contentIdMapping.put(oldContentId, entity.contentId);

            // Import artifact versions that were waiting for this content
            var artifactsToImport = waitingForContent.stream()
                    .filter(artifactVersion -> artifactVersion.contentId == oldContentId).toList();

            for (ArtifactVersionEntity artifactVersionEntity : artifactsToImport) {
                artifactVersionEntity.contentId = entity.contentId;
                importArtifactVersion(artifactVersionEntity);
            }
            waitingForContent.removeAll(artifactsToImport);

        } catch (Exception ex) {
            log.warn("Failed to import content {}: {}", entity, ex.getMessage());
        }
    }

    @Override
    public void importGlobalRule(GlobalRuleEntity entity) {
        try {
            storage.importGlobalRule(io.apicurio.registry.utils.impexp.v3.GlobalRuleEntity.builder()
                    .configuration(entity.configuration).ruleType(entity.ruleType).build());
            log.debug("Global rule imported successfully: {}", entity);
        } catch (Exception ex) {
            log.warn("Failed to import global rule {}: {}", entity, ex.getMessage());
        }
    }

    @Override
    public void importGroup(GroupEntity entity) {
        try {
            io.apicurio.registry.utils.impexp.v3.GroupEntity newEntity = io.apicurio.registry.utils.impexp.v3.GroupEntity
                    .builder().artifactsType(entity.artifactsType).createdOn(entity.createdOn)
                    .description(entity.description).groupId(entity.groupId).labels(Collections.emptyMap())
                    .modifiedBy(entity.modifiedBy).modifiedOn(entity.modifiedOn).owner(entity.createdBy)
                    .build();
            storage.importGroup(newEntity);
            log.debug("Group imported successfully: {}", entity);
        } catch (Exception ex) {
            log.warn("Failed to import group {}: {}", entity, ex.getMessage());
        }
    }

    @Override
    public void importComment(CommentEntity entity) {
        try {
            if (!globalIdMapping.containsKey(entity.globalId)) {
                // The version hasn't been imported yet. Need to wait for it.
                waitingForVersion.add(entity);
                return;
            }
            entity.globalId = globalIdMapping.get(entity.globalId);

            io.apicurio.registry.utils.impexp.v3.CommentEntity newEntity = io.apicurio.registry.utils.impexp.v3.CommentEntity
                    .builder().commentId(entity.commentId).createdOn(entity.createdOn)
                    .globalId(entity.globalId).owner(entity.createdBy).value(entity.value).build();

            storage.importComment(newEntity);
            log.debug("Comment imported successfully: {}", entity);
        } catch (Exception ex) {
            log.warn("Failed to import comment {}: {}", entity, ex.getMessage());
        }
    }

    /**
     * WARNING: Must be executed within a transaction!
     */
    @Override
    public void importData(EntityInputStream entities, Runnable postImportAction) {
        try {
            Entity entity = null;
            while ((entity = entities.nextEntity()) != null) {
                importEntity(entity);
            }

            postImportAction.run();

            // Make sure the contentId sequence is set high enough
            storage.resetContentId();

            // Make sure the globalId sequence is set high enough
            storage.resetGlobalId();

            // Make sure the commentId sequence is set high enough
            storage.resetCommentId();

        } catch (IOException ex) {
            throw new RegistryException("Could not read next entity to import", ex);
        }
    }

    private String determineContentType(String artifactTypeHint, TypedContent content) {
        if (content.getContentType() != null) {
            return content.getContentType();
        } else {
            switch (artifactTypeHint) {
                case ASYNCAPI:
                case JSON:
                case OPENAPI:
                case AVRO:
                    // WARNING: This is only safe here. We can safely return JSON because in V2 we were
                    // transforming all YAML to JSON before storing the content in the database.
                    return ContentTypes.APPLICATION_JSON;
                case PROTOBUF:
                    return ContentTypes.APPLICATION_PROTOBUF;
                case GRAPHQL:
                    return ContentTypes.APPLICATION_GRAPHQL;
                case XML:
                case XSD:
                case WSDL:
                    return ContentTypes.APPLICATION_XML;
            }
        }
        throw new InvalidArtifactTypeException("Invalid or unknown artifact type: " + artifactTypeHint);
    }
}