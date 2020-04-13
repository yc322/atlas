/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.store.graph.v2;


import org.apache.atlas.AtlasConfiguration;
import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.GraphTransactionInterceptor;
import org.apache.atlas.RequestContext;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.TimeBoundary;
import org.apache.atlas.model.TypeCategory;
import org.apache.atlas.model.instance.AtlasClassification;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.model.instance.AtlasRelatedObjectId;
import org.apache.atlas.model.instance.AtlasRelationship;
import org.apache.atlas.model.instance.AtlasStruct;
import org.apache.atlas.model.instance.EntityMutationResponse;
import org.apache.atlas.model.instance.EntityMutations.EntityOperation;
import org.apache.atlas.model.typedef.AtlasStructDef.AtlasAttributeDef;
import org.apache.atlas.model.typedef.AtlasStructDef.AtlasAttributeDef.Cardinality;
import org.apache.atlas.repository.Constants;
import org.apache.atlas.repository.RepositoryException;
import org.apache.atlas.repository.converters.AtlasInstanceConverter;
import org.apache.atlas.repository.graph.FullTextMapperV2;
import org.apache.atlas.repository.graph.GraphHelper;
import org.apache.atlas.repository.graphdb.AtlasEdge;
import org.apache.atlas.repository.graphdb.AtlasEdgeDirection;
import org.apache.atlas.repository.graphdb.AtlasGraph;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.store.graph.AtlasRelationshipStore;
import org.apache.atlas.repository.store.graph.EntityGraphDiscoveryContext;
import org.apache.atlas.repository.store.graph.v1.DeleteHandlerDelegate;
import org.apache.atlas.type.AtlasArrayType;
import org.apache.atlas.type.AtlasBuiltInTypes;
import org.apache.atlas.type.AtlasClassificationType;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasMapType;
import org.apache.atlas.type.AtlasNamespaceType.AtlasNamespaceAttribute;
import org.apache.atlas.type.AtlasStructType;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute.AtlasRelationshipEdgeDirection;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.type.AtlasTypeUtil;
import org.apache.atlas.utils.AtlasEntityUtil;
import org.apache.atlas.utils.AtlasJson;
import org.apache.atlas.utils.AtlasPerfMetrics.MetricRecorder;
import org.apache.atlas.utils.AtlasPerfTracer;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.atlas.AtlasConfiguration.LABEL_MAX_LENGTH;
import static org.apache.atlas.model.TypeCategory.CLASSIFICATION;
import static org.apache.atlas.model.instance.AtlasEntity.Status.ACTIVE;
import static org.apache.atlas.model.instance.AtlasEntity.Status.DELETED;
import static org.apache.atlas.model.instance.AtlasRelatedObjectId.KEY_RELATIONSHIP_ATTRIBUTES;
import static org.apache.atlas.model.instance.EntityMutations.EntityOperation.CREATE;
import static org.apache.atlas.model.instance.EntityMutations.EntityOperation.DELETE;
import static org.apache.atlas.model.instance.EntityMutations.EntityOperation.PARTIAL_UPDATE;
import static org.apache.atlas.model.instance.EntityMutations.EntityOperation.UPDATE;
import static org.apache.atlas.model.typedef.AtlasStructDef.AtlasAttributeDef.Cardinality.SET;
import static org.apache.atlas.repository.Constants.*;
import static org.apache.atlas.repository.graph.GraphHelper.getCollectionElementsUsingRelationship;
import static org.apache.atlas.repository.graph.GraphHelper.getClassificationEdge;
import static org.apache.atlas.repository.graph.GraphHelper.getClassificationVertex;
import static org.apache.atlas.repository.graph.GraphHelper.getDefaultRemovePropagations;
import static org.apache.atlas.repository.graph.GraphHelper.getDelimitedClassificationNames;
import static org.apache.atlas.repository.graph.GraphHelper.getLabels;
import static org.apache.atlas.repository.graph.GraphHelper.getMapElementsProperty;
import static org.apache.atlas.repository.graph.GraphHelper.getStatus;
import static org.apache.atlas.repository.graph.GraphHelper.getTraitLabel;
import static org.apache.atlas.repository.graph.GraphHelper.getTraitNames;
import static org.apache.atlas.repository.graph.GraphHelper.getTypeName;
import static org.apache.atlas.repository.graph.GraphHelper.getTypeNames;
import static org.apache.atlas.repository.graph.GraphHelper.isActive;
import static org.apache.atlas.repository.graph.GraphHelper.isPropagationEnabled;
import static org.apache.atlas.repository.graph.GraphHelper.isRelationshipEdge;
import static org.apache.atlas.repository.graph.GraphHelper.string;
import static org.apache.atlas.repository.graph.GraphHelper.updateModificationMetadata;
import static org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2.getIdFromVertex;
import static org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2.isReference;
import static org.apache.atlas.type.AtlasStructType.AtlasAttribute.AtlasRelationshipEdgeDirection.IN;
import static org.apache.atlas.type.AtlasStructType.AtlasAttribute.AtlasRelationshipEdgeDirection.OUT;

@Component
public class EntityGraphMapper {
    private static final Logger LOG      = LoggerFactory.getLogger(EntityGraphMapper.class);
    private static final Logger PERF_LOG = AtlasPerfTracer.getPerfLogger("entityGraphMapper");

    private static final String  SOFT_REF_FORMAT                   = "%s:%s";
    private static final int     INDEXED_STR_SAFE_LEN              = AtlasConfiguration.GRAPHSTORE_INDEXED_STRING_SAFE_LENGTH.getInt();
    private static final boolean WARN_ON_NO_RELATIONSHIP           = AtlasConfiguration.RELATIONSHIP_WARN_NO_RELATIONSHIPS.getBoolean();
    private static final String  CLASSIFICATION_NAME_DELIMITER     = "|";
    private static final Pattern CUSTOM_ATTRIBUTE_KEY_REGEX        = Pattern.compile("^[a-zA-Z0-9_-]*$");
    private static final Pattern LABEL_REGEX                       = Pattern.compile("^[a-zA-Z0-9_-]*$");
    private static final int     CUSTOM_ATTRIBUTE_KEY_MAX_LENGTH   = AtlasConfiguration.CUSTOM_ATTRIBUTE_KEY_MAX_LENGTH.getInt();
    private static final int     CUSTOM_ATTRIBUTE_VALUE_MAX_LENGTH = AtlasConfiguration.CUSTOM_ATTRIBUTE_VALUE_MAX_LENGTH.getInt();

    private static final boolean ENTITY_CHANGE_NOTIFY_IGNORE_RELATIONSHIP_ATTRIBUTES = AtlasConfiguration.ENTITY_CHANGE_NOTIFY_IGNORE_RELATIONSHIP_ATTRIBUTES.getBoolean();

    private final GraphHelper               graphHelper = GraphHelper.getInstance();
    private final AtlasGraph                graph;
    private final DeleteHandlerDelegate     deleteDelegate;
    private final AtlasTypeRegistry         typeRegistry;
    private final AtlasRelationshipStore    relationshipStore;
    private final AtlasEntityChangeNotifier entityChangeNotifier;
    private final AtlasInstanceConverter    instanceConverter;
    private final EntityGraphRetriever      entityRetriever;
    private final FullTextMapperV2          fullTextMapperV2;

    @Inject
    public EntityGraphMapper(DeleteHandlerDelegate deleteDelegate, AtlasTypeRegistry typeRegistry, AtlasGraph atlasGraph,
                             AtlasRelationshipStore relationshipStore, AtlasEntityChangeNotifier entityChangeNotifier,
                             AtlasInstanceConverter instanceConverter, FullTextMapperV2 fullTextMapperV2) {
        this.deleteDelegate       = deleteDelegate;
        this.typeRegistry         = typeRegistry;
        this.graph                = atlasGraph;
        this.relationshipStore    = relationshipStore;
        this.entityChangeNotifier = entityChangeNotifier;
        this.instanceConverter    = instanceConverter;
        this.entityRetriever      = new EntityGraphRetriever(typeRegistry);
        this.fullTextMapperV2     = fullTextMapperV2;
    }

    public AtlasVertex createVertex(AtlasEntity entity) throws AtlasBaseException {
        final String guid = UUID.randomUUID().toString();
        return createVertexWithGuid(entity, guid);
    }

    public AtlasVertex createShellEntityVertex(AtlasObjectId objectId, EntityGraphDiscoveryContext context) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> createShellEntityVertex({})", objectId.getTypeName());
        }

        final String    guid       = UUID.randomUUID().toString();
        AtlasEntityType entityType = typeRegistry.getEntityTypeByName(objectId.getTypeName());
        AtlasVertex     ret        = createStructVertex(objectId);

        for (String superTypeName : entityType.getAllSuperTypes()) {
            AtlasGraphUtilsV2.addEncodedProperty(ret, SUPER_TYPES_PROPERTY_KEY, superTypeName);
        }

        AtlasGraphUtilsV2.setEncodedProperty(ret, GUID_PROPERTY_KEY, guid);
        AtlasGraphUtilsV2.setEncodedProperty(ret, VERSION_PROPERTY_KEY, getEntityVersion(null));
        AtlasGraphUtilsV2.setEncodedProperty(ret, IS_INCOMPLETE_PROPERTY_KEY, INCOMPLETE_ENTITY_VALUE);

        // map unique attributes
        Map<String, Object>   uniqueAttributes = objectId.getUniqueAttributes();
        EntityMutationContext mutationContext  = new EntityMutationContext(context);

        for (AtlasAttribute attribute : entityType.getUniqAttributes().values()) {
            String attrName  = attribute.getName();

            if (uniqueAttributes.containsKey(attrName)) {
                Object attrValue = attribute.getAttributeType().getNormalizedValue(uniqueAttributes.get(attrName));

                mapAttribute(attribute, attrValue, ret, CREATE, mutationContext);
            }
        }

        GraphTransactionInterceptor.addToVertexCache(guid, ret);

        return ret;
    }

    public AtlasVertex createVertexWithGuid(AtlasEntity entity, String guid) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> createVertexWithGuid({})", entity.getTypeName());
        }

        AtlasEntityType entityType = typeRegistry.getEntityTypeByName(entity.getTypeName());
        AtlasVertex     ret        = createStructVertex(entity);

        for (String superTypeName : entityType.getAllSuperTypes()) {
            AtlasGraphUtilsV2.addEncodedProperty(ret, SUPER_TYPES_PROPERTY_KEY, superTypeName);
        }

        AtlasGraphUtilsV2.setEncodedProperty(ret, GUID_PROPERTY_KEY, guid);
        AtlasGraphUtilsV2.setEncodedProperty(ret, VERSION_PROPERTY_KEY, getEntityVersion(entity));

        setCustomAttributes(ret, entity);

        setLabels(ret, entity.getLabels());

        GraphTransactionInterceptor.addToVertexCache(guid, ret);

        return ret;
    }

    public void updateSystemAttributes(AtlasVertex vertex, AtlasEntity entity) throws AtlasBaseException {
        if (entity.getVersion() != null) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, VERSION_PROPERTY_KEY, entity.getVersion());
        }

        if (entity.getCreateTime() != null) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, TIMESTAMP_PROPERTY_KEY, entity.getCreateTime().getTime());
        }

        if (entity.getUpdateTime() != null) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, MODIFICATION_TIMESTAMP_PROPERTY_KEY, entity.getUpdateTime().getTime());
        }

        if (StringUtils.isNotEmpty(entity.getCreatedBy())) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, CREATED_BY_KEY, entity.getCreatedBy());
        }

        if (StringUtils.isNotEmpty(entity.getUpdatedBy())) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, MODIFIED_BY_KEY, entity.getUpdatedBy());
        }

        if (StringUtils.isNotEmpty(entity.getHomeId())) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, HOME_ID_KEY, entity.getHomeId());
        }

        if (entity.isProxy() != null) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, IS_PROXY_KEY, entity.isProxy());
        }

        if (entity.getProvenanceType() != null) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, PROVENANCE_TYPE_KEY, entity.getProvenanceType());
        }

        if (entity.getCustomAttributes() != null) {
            setCustomAttributes(vertex, entity);
        }
    }

    public EntityMutationResponse   mapAttributesAndClassifications(EntityMutationContext context, final boolean isPartialUpdate, final boolean replaceClassifications, boolean replaceNamespaceAttributes) throws AtlasBaseException {
        MetricRecorder metric = RequestContext.get().startMetricRecord("mapAttributesAndClassifications");

        EntityMutationResponse resp       = new EntityMutationResponse();
        RequestContext         reqContext = RequestContext.get();

        Collection<AtlasEntity> createdEntities = context.getCreatedEntities();
        Collection<AtlasEntity> updatedEntities = context.getUpdatedEntities();

        if (CollectionUtils.isNotEmpty(createdEntities)) {
            for (AtlasEntity createdEntity : createdEntities) {
                String          guid       = createdEntity.getGuid();
                AtlasVertex     vertex     = context.getVertex(guid);
                AtlasEntityType entityType = context.getType(guid);

                mapRelationshipAttributes(createdEntity, entityType, vertex, CREATE, context);

                mapAttributes(createdEntity, entityType, vertex, CREATE, context);

                resp.addEntity(CREATE, constructHeader(createdEntity, entityType, vertex));
                addClassifications(context, guid, createdEntity.getClassifications());

                addOrUpdateNamespaceAttributes(vertex, entityType, createdEntity.getNamespaceAttributes());

                reqContext.cache(createdEntity);
            }
        }

        if (CollectionUtils.isNotEmpty(updatedEntities)) {
            for (AtlasEntity updatedEntity : updatedEntities) {
                String          guid       = updatedEntity.getGuid();
                AtlasVertex     vertex     = context.getVertex(guid);
                AtlasEntityType entityType = context.getType(guid);

                mapRelationshipAttributes(updatedEntity, entityType, vertex, UPDATE, context);

                mapAttributes(updatedEntity, entityType, vertex, UPDATE, context);

                if (isPartialUpdate) {
                    resp.addEntity(PARTIAL_UPDATE, constructHeader(updatedEntity, entityType, vertex));
                } else {
                    resp.addEntity(UPDATE, constructHeader(updatedEntity, entityType, vertex));
                }

                if (replaceClassifications) {
                    deleteClassifications(guid);
                    addClassifications(context, guid, updatedEntity.getClassifications());
                }

                if (replaceNamespaceAttributes) {
                    setNamespaceAttributes(vertex, entityType, updatedEntity.getNamespaceAttributes());
                }

                reqContext.cache(updatedEntity);
            }
        }

        if (CollectionUtils.isNotEmpty(context.getEntitiesToDelete())) {
            deleteDelegate.getHandler().deleteEntities(context.getEntitiesToDelete());
        }

        RequestContext req = RequestContext.get();

        for (AtlasEntityHeader entity : req.getDeletedEntities()) {
            resp.addEntity(DELETE, entity);
        }

        for (AtlasEntityHeader entity : req.getUpdatedEntities()) {
            if (isPartialUpdate) {
                resp.addEntity(PARTIAL_UPDATE, entity);
            }
            else {
                resp.addEntity(UPDATE, entity);
            }
        }

        RequestContext.get().endMetricRecord(metric);

        return resp;
    }

    public void setCustomAttributes(AtlasVertex vertex, AtlasEntity entity) {
        String customAttributesString = getCustomAttributesString(entity);

        if (customAttributesString != null) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, CUSTOM_ATTRIBUTES_PROPERTY_KEY, customAttributesString);
        }
    }

    public void setLabels(AtlasVertex vertex, Set<String> labels) throws AtlasBaseException {
        final Set<String> currentLabels = getLabels(vertex);
        final Set<String> addedLabels;
        final Set<String> removedLabels;

        if (CollectionUtils.isEmpty(currentLabels)) {
            addedLabels   = labels;
            removedLabels = null;
        } else if (CollectionUtils.isEmpty(labels)) {
            addedLabels   = null;
            removedLabels = currentLabels;
        } else {
            addedLabels   = new HashSet<String>(CollectionUtils.subtract(labels, currentLabels));
            removedLabels = new HashSet<String>(CollectionUtils.subtract(currentLabels, labels));
        }

        updateLabels(vertex, labels);

        if (entityChangeNotifier != null) {
            entityChangeNotifier.onLabelsUpdatedFromEntity(GraphHelper.getGuid(vertex), addedLabels, removedLabels);
        }
    }

    public void addLabels(AtlasVertex vertex, Set<String> labels) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(labels)) {
            final Set<String> existingLabels = GraphHelper.getLabels(vertex);
            final Set<String> updatedLabels;

            if (CollectionUtils.isEmpty(existingLabels)) {
                updatedLabels = labels;
            } else {
                updatedLabels = new HashSet<>(existingLabels);
                updatedLabels.addAll(labels);
            }
            if (!updatedLabels.equals(existingLabels)) {
                updateLabels(vertex, updatedLabels);
                updatedLabels.removeAll(existingLabels);

                if (entityChangeNotifier != null) {
                    entityChangeNotifier.onLabelsUpdatedFromEntity(GraphHelper.getGuid(vertex), updatedLabels, null);
                }
            }
        }
    }

    public void removeLabels(AtlasVertex vertex, Set<String> labels) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(labels)) {
            final Set<String> existingLabels = GraphHelper.getLabels(vertex);
            Set<String> updatedLabels;

            if (CollectionUtils.isNotEmpty(existingLabels)) {
                updatedLabels = new HashSet<>(existingLabels);
                updatedLabels.removeAll(labels);

                if (!updatedLabels.equals(existingLabels)) {
                    updateLabels(vertex, updatedLabels);
                    existingLabels.removeAll(updatedLabels);

                    if (entityChangeNotifier != null) {
                        entityChangeNotifier.onLabelsUpdatedFromEntity(GraphHelper.getGuid(vertex), null, existingLabels);
                    }
                }
            }
        }
    }

    private void updateLabels(AtlasVertex vertex, Set<String> labels) {
        if (CollectionUtils.isNotEmpty(labels)) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, LABELS_PROPERTY_KEY, getLabelString(labels));
        } else {
            vertex.removeProperty(LABELS_PROPERTY_KEY);
        }
    }

    private String getLabelString(Collection<String> labels) {
        String ret = null;

        if (!labels.isEmpty()) {
            ret = LABEL_NAME_DELIMITER + String.join(LABEL_NAME_DELIMITER, labels) + LABEL_NAME_DELIMITER;
        }

        return ret;
    }

    /*
     * reset/overwrite namespace attributes of the entity with given values
     */
    public void setNamespaceAttributes(AtlasVertex entityVertex, AtlasEntityType entityType, Map<String, Map<String, Object>> entityNamespaces) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> setNamespaceAttributes(entityVertex={}, entityType={}, entityNamespaces={}", entityVertex, entityType.getTypeName(), entityNamespaces);
        }

        Map<String, Map<String, AtlasNamespaceAttribute>> entityTypeNamespaces = entityType.getNamespaceAttributes();

        for (Map.Entry<String, Map<String, AtlasNamespaceAttribute>> entry : entityTypeNamespaces.entrySet()) {
            String                               nsName                 = entry.getKey();
            Map<String, AtlasNamespaceAttribute> entityTypeNsAttributes = entry.getValue();
            Map<String, Object>                  entityNsAttributes     = MapUtils.isEmpty(entityNamespaces) ? null : entityNamespaces.get(nsName);

            for (AtlasNamespaceAttribute nsAttribute : entityTypeNsAttributes.values()) {
                String nsAttrName          = nsAttribute.getName();
                Object nsAttrExistingValue = entityVertex.getProperty(nsAttribute.getVertexPropertyName(), Object.class);
                Object nsAttrNewValue      = MapUtils.isEmpty(entityNsAttributes) ? null : entityNsAttributes.get(nsAttrName);

                if (nsAttrExistingValue == null) {
                    if (nsAttrNewValue != null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("setNamespaceAttributes(): adding {}.{}={}", nsName, nsAttribute.getName(), nsAttrNewValue);
                        }

                        mapAttribute(nsAttribute, nsAttrNewValue, entityVertex, CREATE, new EntityMutationContext());
                    }
                } else {
                    if (nsAttrNewValue != null) {
                        if (!Objects.equals(nsAttrExistingValue, nsAttrNewValue)) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("setNamespaceAttributes(): updating {}.{}={}", nsName, nsAttribute.getName(), nsAttrNewValue);
                            }

                            mapAttribute(nsAttribute, nsAttrNewValue, entityVertex, UPDATE, new EntityMutationContext());
                        }
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("setNamespaceAttributes(): removing {}.{}", nsName, nsAttribute.getName());
                        }

                        entityVertex.removeProperty(nsAttribute.getVertexPropertyName());
                    }
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== setNamespaceAttributes(entityVertex={}, entityType={}, entityNamespaces={}", entityVertex, entityType.getTypeName(), entityNamespaces);
        }
    }

    /*
     * add or update the given namespace attributes on the entity
     */
    public void addOrUpdateNamespaceAttributes(AtlasVertex entityVertex, AtlasEntityType entityType, Map<String, Map<String, Object>> entityNamespaces) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> addOrUpdateNamespaceAttributes(entityVertex={}, entityType={}, entityNamespaces={}", entityVertex, entityType.getTypeName(), entityNamespaces);
        }

        Map<String, Map<String, AtlasNamespaceAttribute>> entityTypeNamespaces = entityType.getNamespaceAttributes();

        if (MapUtils.isNotEmpty(entityTypeNamespaces) && MapUtils.isNotEmpty(entityNamespaces)) {
            for (Map.Entry<String, Map<String, AtlasNamespaceAttribute>> entry : entityTypeNamespaces.entrySet()) {
                String                               nsName                 = entry.getKey();
                Map<String, AtlasNamespaceAttribute> entityTypeNsAttributes = entry.getValue();
                Map<String, Object>                  entityNsAttributes     = entityNamespaces.get(nsName);

                if (MapUtils.isEmpty(entityNsAttributes)) {
                    continue;
                }

                for (AtlasNamespaceAttribute nsAttribute : entityTypeNsAttributes.values()) {
                    String nsAttrName = nsAttribute.getName();

                    if (!entityNsAttributes.containsKey(nsAttrName)) {
                        continue;
                    }

                    Object nsAttrValue   = entityNsAttributes.get(nsAttrName);
                    Object existingValue = AtlasGraphUtilsV2.getEncodedProperty(entityVertex, nsAttribute.getVertexPropertyName(), Object.class);

                    if (existingValue == null) {
                        if (nsAttrValue != null) {
                            mapAttribute(nsAttribute, nsAttrValue, entityVertex, CREATE, new EntityMutationContext());
                        }
                    } else {
                        if (!Objects.equals(existingValue, nsAttrValue)) {
                            mapAttribute(nsAttribute, nsAttrValue, entityVertex, UPDATE, new EntityMutationContext());
                        }
                    }
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== addOrUpdateNamespaceAttributes(entityVertex={}, entityType={}, entityNamespaces={}", entityVertex, entityType.getTypeName(), entityNamespaces);
        }
    }

    /*
     * remove the given namespace attributes from the entity
     */
    public void removeNamespaceAttributes(AtlasVertex entityVertex, AtlasEntityType entityType, Map<String, Map<String, Object>> entityNamespaces) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> removeNamespaceAttributes(entityVertex={}, entityType={}, entityNamespaces={}", entityVertex, entityType.getTypeName(), entityNamespaces);
        }

        Map<String, Map<String, AtlasNamespaceAttribute>> entityTypeNamespaces = entityType.getNamespaceAttributes();

        if (MapUtils.isNotEmpty(entityTypeNamespaces) && MapUtils.isNotEmpty(entityNamespaces)) {
            for (Map.Entry<String, Map<String, AtlasNamespaceAttribute>> entry : entityTypeNamespaces.entrySet()) {
                String                               nsName                 = entry.getKey();
                Map<String, AtlasNamespaceAttribute> entityTypeNsAttributes = entry.getValue();

                if (!entityNamespaces.containsKey(nsName)) { // nothing to remove for this namespace
                    continue;
                }

                Map<String, Object> entityNsAttributes = entityNamespaces.get(nsName);

                for (AtlasNamespaceAttribute nsAttribute : entityTypeNsAttributes.values()) {
                    // if (entityNsAttributes is empty) remove all attributes in this namespace
                    // else remove the attribute only if its given in entityNsAttributes
                    if (MapUtils.isEmpty(entityNsAttributes) || entityNsAttributes.containsKey(nsAttribute.getName())) {
                        entityVertex.removeProperty(nsAttribute.getVertexPropertyName());
                    }
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== removeNamespaceAttributes(entityVertex={}, entityType={}, entityNamespaces={}", entityVertex, entityType.getTypeName(), entityNamespaces);
        }
    }

    private AtlasVertex createStructVertex(AtlasStruct struct) {
        return createStructVertex(struct.getTypeName());
    }

    private AtlasVertex createStructVertex(AtlasObjectId objectId) {
        return createStructVertex(objectId.getTypeName());
    }

    private AtlasVertex createStructVertex(String typeName) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> createStructVertex({})", typeName);
        }

        final AtlasVertex ret = graph.addVertex();

        AtlasGraphUtilsV2.setEncodedProperty(ret, ENTITY_TYPE_PROPERTY_KEY, typeName);
        AtlasGraphUtilsV2.setEncodedProperty(ret, STATE_PROPERTY_KEY, AtlasEntity.Status.ACTIVE.name());
        AtlasGraphUtilsV2.setEncodedProperty(ret, TIMESTAMP_PROPERTY_KEY, RequestContext.get().getRequestTime());
        AtlasGraphUtilsV2.setEncodedProperty(ret, MODIFICATION_TIMESTAMP_PROPERTY_KEY, RequestContext.get().getRequestTime());
        AtlasGraphUtilsV2.setEncodedProperty(ret, CREATED_BY_KEY, RequestContext.get().getUser());
        AtlasGraphUtilsV2.setEncodedProperty(ret, MODIFIED_BY_KEY, RequestContext.get().getUser());

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== createStructVertex({})", typeName);
        }

        return ret;
    }

    private AtlasVertex createClassificationVertex(AtlasClassification classification) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> createVertex({})", classification.getTypeName());
        }

        AtlasClassificationType classificationType = typeRegistry.getClassificationTypeByName(classification.getTypeName());

        AtlasVertex ret = createStructVertex(classification);

        AtlasGraphUtilsV2.addEncodedProperty(ret, SUPER_TYPES_PROPERTY_KEY, classificationType.getAllSuperTypes());
        AtlasGraphUtilsV2.setEncodedProperty(ret, CLASSIFICATION_ENTITY_GUID, classification.getEntityGuid());
        AtlasGraphUtilsV2.setEncodedProperty(ret, CLASSIFICATION_ENTITY_STATUS, classification.getEntityStatus().name());

        return ret;
    }

    private void mapAttributes(AtlasStruct struct, AtlasVertex vertex, EntityOperation op, EntityMutationContext context) throws AtlasBaseException {
        mapAttributes(struct, getStructType(struct.getTypeName()), vertex, op, context);
    }

    // struct -> updatedEntity
    // mapAttributes(updatedEntity, entityType, vertex, UPDATE, context)
    private void mapAttributes(AtlasStruct struct, AtlasStructType structType, AtlasVertex vertex, EntityOperation op, EntityMutationContext context) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> mapAttributes({}, {})", op, struct.getTypeName());
        }

        if (MapUtils.isNotEmpty(struct.getAttributes())) {
            MetricRecorder metric = RequestContext.get().startMetricRecord("mapAttributes");

            if (op.equals(CREATE)) {
                for (AtlasAttribute attribute : structType.getAllAttributes().values()) {
                    Object attrValue = struct.getAttribute(attribute.getName());

                    mapAttribute(attribute, attrValue, vertex, op, context);
                }

            } else if (op.equals(UPDATE)) {
                for (String attrName : struct.getAttributes().keySet()) {
                    AtlasAttribute attribute = structType.getAttribute(attrName);

                    if (attribute != null) {
                        Object attrValue = struct.getAttribute(attrName);

                        mapAttribute(attribute, attrValue, vertex, op, context);
                    } else {
                        LOG.warn("mapAttributes(): invalid attribute {}.{}. Ignored..", struct.getTypeName(), attrName);
                    }
                }
            }

            updateModificationMetadata(vertex);

            RequestContext.get().endMetricRecord(metric);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== mapAttributes({}, {})", op, struct.getTypeName());
        }
    }

    private void mapRelationshipAttributes(AtlasEntity entity, AtlasEntityType entityType, AtlasVertex vertex, EntityOperation op,
                                           EntityMutationContext context) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> mapRelationshipAttributes({}, {})", op, entity.getTypeName());
        }

        if (MapUtils.isNotEmpty(entity.getRelationshipAttributes())) {
            MetricRecorder metric = RequestContext.get().startMetricRecord("mapRelationshipAttributes");

            if (op.equals(CREATE)) {
                for (String attrName : entityType.getRelationshipAttributes().keySet()) {
                    Object         attrValue    = entity.getRelationshipAttribute(attrName);
                    String         relationType = AtlasEntityUtil.getRelationshipType(attrValue);
                    AtlasAttribute attribute    = entityType.getRelationshipAttribute(attrName, relationType);

                    mapAttribute(attribute, attrValue, vertex, op, context);
                }

            } else if (op.equals(UPDATE)) {
                // relationship attributes mapping
                for (String attrName : entityType.getRelationshipAttributes().keySet()) {
                    if (entity.hasRelationshipAttribute(attrName)) {
                        Object         attrValue    = entity.getRelationshipAttribute(attrName);
                        String         relationType = AtlasEntityUtil.getRelationshipType(attrValue);
                        AtlasAttribute attribute    = entityType.getRelationshipAttribute(attrName, relationType);

                        mapAttribute(attribute, attrValue, vertex, op, context);
                    }
                }
            }

            updateModificationMetadata(vertex);

            RequestContext.get().endMetricRecord(metric);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== mapRelationshipAttributes({}, {})", op, entity.getTypeName());
        }
    }

    private void mapAttribute(AtlasAttribute attribute, Object attrValue, AtlasVertex vertex, EntityOperation op, EntityMutationContext context) throws AtlasBaseException {
        if (attrValue == null) {
            AtlasAttributeDef attributeDef = attribute.getAttributeDef();
            AtlasType         attrType     = attribute.getAttributeType();

            if (attrType.getTypeCategory() == TypeCategory.PRIMITIVE) {
                if (attributeDef.getDefaultValue() != null) {
                    attrValue = attrType.createDefaultValue(attributeDef.getDefaultValue());
                } else {
                    if (attribute.getAttributeDef().getIsOptional()) {
                        attrValue = attrType.createOptionalDefaultValue();
                    } else {
                        attrValue = attrType.createDefaultValue();
                    }
                }
            }
        }

        AttributeMutationContext ctx = new AttributeMutationContext(op, vertex, attribute, attrValue);

        mapToVertexByTypeCategory(ctx, context);
    }

    private Object mapToVertexByTypeCategory(AttributeMutationContext ctx, EntityMutationContext context) throws AtlasBaseException {
        if (ctx.getOp() == CREATE && ctx.getValue() == null) {
            return null;
        }

        switch (ctx.getAttrType().getTypeCategory()) {
            case PRIMITIVE:
            case ENUM:
                return mapPrimitiveValue(ctx, context);

            case STRUCT: {
                String    edgeLabel   = AtlasGraphUtilsV2.getEdgeLabel(ctx.getVertexProperty());
                AtlasEdge currentEdge = graphHelper.getEdgeForLabel(ctx.getReferringVertex(), edgeLabel);
                AtlasEdge edge        = currentEdge != null ? currentEdge : null;

                ctx.setExistingEdge(edge);

                AtlasEdge newEdge = mapStructValue(ctx, context);

                if (currentEdge != null && !currentEdge.equals(newEdge)) {
                    deleteDelegate.getHandler().deleteEdgeReference(currentEdge, ctx.getAttrType().getTypeCategory(), false, true, ctx.getReferringVertex());
                }

                return newEdge;
            }

            case OBJECT_ID_TYPE: {
                if (ctx.getAttributeDef().isSoftReferenced()) {
                    return mapSoftRefValueWithUpdate(ctx, context);
                }

                AtlasRelationshipEdgeDirection edgeDirection = ctx.getAttribute().getRelationshipEdgeDirection();
                String edgeLabel = ctx.getAttribute().getRelationshipEdgeLabel();

                // if relationshipDefs doesn't exist, use legacy way of finding edge label.
                if (StringUtils.isEmpty(edgeLabel)) {
                    edgeLabel = AtlasGraphUtilsV2.getEdgeLabel(ctx.getVertexProperty());
                }

                String    relationshipGuid = getRelationshipGuid(ctx.getValue());
                AtlasEdge currentEdge;

                // if relationshipGuid is assigned in AtlasRelatedObjectId use it to fetch existing AtlasEdge
                if (StringUtils.isNotEmpty(relationshipGuid) && !RequestContext.get().isImportInProgress()) {
                    currentEdge = graphHelper.getEdgeForGUID(relationshipGuid);
                } else {
                    currentEdge = graphHelper.getEdgeForLabel(ctx.getReferringVertex(), edgeLabel, edgeDirection);
                }

                AtlasEdge newEdge = null;

                if (ctx.getValue() != null) {
                    AtlasEntityType instanceType = getInstanceType(ctx.getValue(), context);
                    AtlasEdge       edge         = currentEdge != null ? currentEdge : null;

                    ctx.setElementType(instanceType);
                    ctx.setExistingEdge(edge);

                    newEdge = mapObjectIdValueUsingRelationship(ctx, context);

                    // legacy case update inverse attribute
                    if (ctx.getAttribute().getInverseRefAttribute() != null) {
                        // Update the inverse reference using relationship on the target entity
                        addInverseReference(context, ctx.getAttribute().getInverseRefAttribute(), newEdge, getRelationshipAttributes(ctx.getValue()));
                    }
                }

                // created new relationship,
                // record entity update on both vertices of the new relationship
                if (currentEdge == null && newEdge != null) {

                    // based on relationship edge direction record update only on attribute vertex
                    if (edgeDirection == IN) {
                        recordEntityUpdate(newEdge.getOutVertex());

                    } else {
                        recordEntityUpdate(newEdge.getInVertex());
                    }
                }

                // update references, if current and new edge don't match
                // record entity update on new reference and delete(edge) old reference.
                if (currentEdge != null && !currentEdge.equals(newEdge)) {

                    //record entity update on new edge
                    if (isRelationshipEdge(newEdge)) {
                        AtlasVertex attrVertex = context.getDiscoveryContext().getResolvedEntityVertex(getGuid(ctx.getValue()));

                        recordEntityUpdate(attrVertex);
                    }

                    //delete old reference
                    deleteDelegate.getHandler().deleteEdgeReference(currentEdge, ctx.getAttrType().getTypeCategory(), ctx.getAttribute().isOwnedRef(),
                                                      true, ctx.getAttribute().getRelationshipEdgeDirection(), ctx.getReferringVertex());
                }

                return newEdge;
            }

            case MAP:
                return mapMapValue(ctx, context);

            case ARRAY:
                return mapArrayValue(ctx, context);

            default:
                throw new AtlasBaseException(AtlasErrorCode.TYPE_CATEGORY_INVALID, ctx.getAttrType().getTypeCategory().name());
        }
    }

    private String mapSoftRefValue(AttributeMutationContext ctx, EntityMutationContext context) {
        String ret = null;

        if (ctx.getValue() instanceof AtlasObjectId) {
            AtlasObjectId objectId = (AtlasObjectId) ctx.getValue();
            String        typeName = objectId.getTypeName();
            String        guid     = AtlasTypeUtil.isUnAssignedGuid(objectId.getGuid()) ? context.getGuidAssignments().get(objectId.getGuid()) : objectId.getGuid();

            ret = AtlasEntityUtil.formatSoftRefValue(typeName, guid);
        } else {
            if (ctx.getValue() != null) {
                LOG.warn("mapSoftRefValue: Was expecting AtlasObjectId, but found: {}", ctx.getValue().getClass());
            }
        }

        setAssignedGuid(ctx.getValue(), context);

        return ret;
    }

    private Object mapSoftRefValueWithUpdate(AttributeMutationContext ctx, EntityMutationContext context) {
        String softRefValue = mapSoftRefValue(ctx, context);

        AtlasGraphUtilsV2.setProperty(ctx.getReferringVertex(), ctx.getVertexProperty(), softRefValue);

        return softRefValue;
    }

    private void addInverseReference(EntityMutationContext context, AtlasAttribute inverseAttribute, AtlasEdge edge, Map<String, Object> relationshipAttributes) throws AtlasBaseException {
        AtlasStructType inverseType      = inverseAttribute.getDefinedInType();
        AtlasVertex     inverseVertex    = edge.getInVertex();
        String          inverseEdgeLabel = inverseAttribute.getRelationshipEdgeLabel();
        AtlasEdge       inverseEdge      = graphHelper.getEdgeForLabel(inverseVertex, inverseEdgeLabel);
        String          propertyName     = AtlasGraphUtilsV2.getQualifiedAttributePropertyKey(inverseType, inverseAttribute.getName());

        // create new inverse reference
        AtlasEdge newEdge = createInverseReferenceUsingRelationship(context, inverseAttribute, edge, relationshipAttributes);

        boolean inverseUpdated = true;
        switch (inverseAttribute.getAttributeType().getTypeCategory()) {
        case OBJECT_ID_TYPE:
            if (inverseEdge != null) {
                if (!inverseEdge.equals(newEdge)) {
                    // Disconnect old reference
                    deleteDelegate.getHandler().deleteEdgeReference(inverseEdge, inverseAttribute.getAttributeType().getTypeCategory(),
                                                      inverseAttribute.isOwnedRef(), true, inverseVertex);
                }
                else {
                    // Edge already exists for this attribute between these vertices.
                    inverseUpdated = false;
                }
            }
            break;
        case ARRAY:
            // Add edge ID to property value
            List<String> elements = inverseVertex.getProperty(propertyName, List.class);
            if (newEdge != null && elements == null) {
                elements = new ArrayList<>();
                elements.add(newEdge.getId().toString());
                inverseVertex.setProperty(propertyName, elements);
            }
            else {
               if (newEdge != null && !elements.contains(newEdge.getId().toString())) {
                    elements.add(newEdge.getId().toString());
                    inverseVertex.setProperty(propertyName, elements);
               }
               else {
                   // Property value list already contains the edge ID.
                   inverseUpdated = false;
               }
            }
            break;
        default:
            break;
        }

        if (inverseUpdated) {
            RequestContext requestContext = RequestContext.get();

            if (!requestContext.isDeletedEntity(GraphHelper.getGuid(inverseVertex))) {
                updateModificationMetadata(inverseVertex);

                requestContext.recordEntityUpdate(entityRetriever.toAtlasEntityHeader(inverseVertex));
            }
        }
    }

    private AtlasEdge createInverseReferenceUsingRelationship(EntityMutationContext context, AtlasAttribute inverseAttribute, AtlasEdge edge, Map<String, Object> relationshipAttributes) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> createInverseReferenceUsingRelationship()");
        }

        String      inverseAttributeName   = inverseAttribute.getName();
        AtlasType   inverseAttributeType   = inverseAttribute.getDefinedInType();
        AtlasVertex inverseVertex          = edge.getInVertex();
        AtlasVertex vertex                 = edge.getOutVertex();
        AtlasEdge   ret;

        if (inverseAttributeType instanceof AtlasEntityType) {
            AtlasEntityType entityType = (AtlasEntityType) inverseAttributeType;

            if (entityType.hasRelationshipAttribute(inverseAttributeName)) {
                String relationshipName = graphHelper.getRelationshipTypeName(inverseVertex, entityType, inverseAttributeName);

                ret = getOrCreateRelationship(inverseVertex, vertex, relationshipName, relationshipAttributes);

            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("No RelationshipDef defined between {} and {} on attribute: {}", inverseAttributeType,
                              AtlasGraphUtilsV2.getTypeName(vertex), inverseAttributeName);
                }
                // if no RelationshipDef found, use legacy way to create edges
                ret = createInverseReference(inverseAttribute, (AtlasStructType) inverseAttributeType, inverseVertex, vertex);
            }
        } else {
            // inverseAttribute not of type AtlasEntityType, use legacy way to create edges
            ret = createInverseReference(inverseAttribute, (AtlasStructType) inverseAttributeType, inverseVertex, vertex);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== createInverseReferenceUsingRelationship()");
        }

        updateRelationshipGuidForImport(context, inverseAttributeName, inverseVertex, ret);

        return ret;
    }

    private void updateRelationshipGuidForImport(EntityMutationContext context, String inverseAttributeName, AtlasVertex inverseVertex, AtlasEdge edge) throws AtlasBaseException {
        if (!RequestContext.get().isImportInProgress()) {
            return;
        }

        String parentGuid = GraphHelper.getGuid(inverseVertex);
        if(StringUtils.isEmpty(parentGuid)) {
            return;
        }

        AtlasEntity entity = context.getCreatedOrUpdatedEntity(parentGuid);
        if(entity == null) {
            return;
        }

        String parentRelationshipGuid = getRelationshipGuid(entity.getRelationshipAttribute(inverseAttributeName));
        if(StringUtils.isEmpty(parentRelationshipGuid)) {
            return;
        }

        AtlasGraphUtilsV2.setEncodedProperty(edge, RELATIONSHIP_GUID_PROPERTY_KEY, parentRelationshipGuid);
    }

    // legacy method to create edges for inverse reference
    private AtlasEdge createInverseReference(AtlasAttribute inverseAttribute, AtlasStructType inverseAttributeType,
                                             AtlasVertex inverseVertex, AtlasVertex vertex) throws AtlasBaseException {

        String propertyName     = AtlasGraphUtilsV2.getQualifiedAttributePropertyKey(inverseAttributeType, inverseAttribute.getName());
        String inverseEdgeLabel = AtlasGraphUtilsV2.getEdgeLabel(propertyName);
        AtlasEdge ret;

        try {
            ret = graphHelper.getOrCreateEdge(inverseVertex, vertex, inverseEdgeLabel);

        } catch (RepositoryException e) {
            throw new AtlasBaseException(AtlasErrorCode.INTERNAL_ERROR, e);
        }

        return ret;
    }

    private Object mapPrimitiveValue(AttributeMutationContext ctx, EntityMutationContext context) {
        boolean isIndexableStrAttr = ctx.getAttributeDef().getIsIndexable() && ctx.getAttrType() instanceof AtlasBuiltInTypes.AtlasStringType;

        Object ret = ctx.getValue();

        // Janus bug, when an indexed string attribute has a value longer than a certain length then the reverse indexed key generated by JanusGraph
        // exceeds the HBase row length's hard limit (Short.MAX). This trimming and hashing procedure is to circumvent that limitation
        if (ret != null && isIndexableStrAttr) {
            String value = ret.toString();

            if (value.length() > INDEXED_STR_SAFE_LEN) {
                RequestContext requestContext = RequestContext.get();

                final int trimmedLength;

                if (requestContext.getAttemptCount() <= 1) { // if this is the first attempt, try saving as it is; trim on retry
                    trimmedLength = value.length();
                } else if (requestContext.getAttemptCount() >= requestContext.getMaxAttempts()) { // if this is the last attempt, set to 'safe_len'
                    trimmedLength = INDEXED_STR_SAFE_LEN;
                } else if (requestContext.getAttemptCount() == 2) { // based on experimentation, string length of 4 times 'safe_len' succeeds
                    trimmedLength = Math.min(4 * INDEXED_STR_SAFE_LEN, value.length());
                } else if (requestContext.getAttemptCount() == 3) { // if length of 4 times 'safe_len' failed, try twice 'safe_len'
                    trimmedLength = Math.min(2 * INDEXED_STR_SAFE_LEN, value.length());
                } else { // if twice the 'safe_len' failed, trim to 'safe_len'
                    trimmedLength = INDEXED_STR_SAFE_LEN;
                }

                if (trimmedLength < value.length()) {
                    LOG.warn("Length of indexed attribute {} is {} characters, longer than safe-limit {}; trimming to {} - attempt #{}", ctx.getAttribute().getQualifiedName(), value.length(), INDEXED_STR_SAFE_LEN, trimmedLength, requestContext.getAttemptCount());

                    String checksumSuffix = ":" + DigestUtils.shaHex(value); // Storing SHA checksum in case verification is needed after retrieval

                    ret = value.substring(0, trimmedLength - checksumSuffix.length()) + checksumSuffix;
                } else {
                    LOG.warn("Length of indexed attribute {} is {} characters, longer than safe-limit {}", ctx.getAttribute().getQualifiedName(), value.length(), INDEXED_STR_SAFE_LEN);
                }
            }
        }

        AtlasGraphUtilsV2.setEncodedProperty(ctx.getReferringVertex(), ctx.getVertexProperty(), ret);

        String uniqPropName = ctx.getAttribute() != null ? ctx.getAttribute().getVertexUniquePropertyName() : null;

        if (uniqPropName != null) {
            if (context.isDeletedEntity(ctx.getReferringVertex()) || AtlasGraphUtilsV2.getState(ctx.getReferringVertex()) == DELETED) {
                ctx.getReferringVertex().removeProperty(uniqPropName);
            } else {
                AtlasGraphUtilsV2.setEncodedProperty(ctx.getReferringVertex(), uniqPropName, ret);
            }
        }

        return ret;
    }

    private AtlasEdge mapStructValue(AttributeMutationContext ctx, EntityMutationContext context) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> mapStructValue({})", ctx);
        }

        AtlasEdge ret = null;

        if (ctx.getCurrentEdge() != null) {
            AtlasStruct structVal = null;
            if (ctx.getValue() instanceof AtlasStruct) {
                structVal = (AtlasStruct)ctx.getValue();
            } else if (ctx.getValue() instanceof Map) {
                structVal = new AtlasStruct(ctx.getAttrType().getTypeName(), (Map) AtlasTypeUtil.toStructAttributes((Map)ctx.getValue()));
            }

            if (structVal != null) {
                updateVertex(structVal, ctx.getCurrentEdge().getInVertex(), context);
            }

            ret = ctx.getCurrentEdge();
        } else if (ctx.getValue() != null) {
            String edgeLabel = AtlasGraphUtilsV2.getEdgeLabel(ctx.getVertexProperty());

            AtlasStruct structVal = null;
            if (ctx.getValue() instanceof AtlasStruct) {
                structVal = (AtlasStruct) ctx.getValue();
            } else if (ctx.getValue() instanceof Map) {
                structVal = new AtlasStruct(ctx.getAttrType().getTypeName(), (Map) AtlasTypeUtil.toStructAttributes((Map)ctx.getValue()));
            }

            if (structVal != null) {
                ret = createVertex(structVal, ctx.getReferringVertex(), edgeLabel, context);
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== mapStructValue({})", ctx);
        }

        return ret;
    }

    private AtlasEdge mapObjectIdValue(AttributeMutationContext ctx, EntityMutationContext context) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> mapObjectIdValue({})", ctx);
        }

        AtlasEdge ret = null;

        String guid = getGuid(ctx.getValue());

        AtlasVertex entityVertex = context.getDiscoveryContext().getResolvedEntityVertex(guid);

        if (entityVertex == null) {
            if (AtlasTypeUtil.isAssignedGuid(guid)) {
                entityVertex = context.getVertex(guid);
            }

            if (entityVertex == null) {
                AtlasObjectId objId = getObjectId(ctx.getValue());

                if (objId != null) {
                    entityVertex = context.getDiscoveryContext().getResolvedEntityVertex(objId);
                }
            }
        }

        if (entityVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, (ctx.getValue() == null ? null : ctx.getValue().toString()));
        }

        if (ctx.getCurrentEdge() != null) {
            ret = updateEdge(ctx.getAttributeDef(), ctx.getValue(), ctx.getCurrentEdge(), entityVertex);
        } else if (ctx.getValue() != null) {
            String edgeLabel = AtlasGraphUtilsV2.getEdgeLabel(ctx.getVertexProperty());

            try {
                ret = graphHelper.getOrCreateEdge(ctx.getReferringVertex(), entityVertex, edgeLabel);
            } catch (RepositoryException e) {
                throw new AtlasBaseException(AtlasErrorCode.INTERNAL_ERROR, e);
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== mapObjectIdValue({})", ctx);
        }

        return ret;
    }

    private AtlasEdge mapObjectIdValueUsingRelationship(AttributeMutationContext ctx, EntityMutationContext context) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> mapObjectIdValueUsingRelationship({})", ctx);
        }

        String      guid            = getGuid(ctx.getValue());
        AtlasVertex attributeVertex = context.getDiscoveryContext().getResolvedEntityVertex(guid);
        AtlasVertex entityVertex    = ctx.getReferringVertex();
        AtlasEdge   ret;

        if (attributeVertex == null) {
            if (AtlasTypeUtil.isAssignedGuid(guid)) {
                attributeVertex = context.getVertex(guid);
            }

            if (attributeVertex == null) {
                AtlasObjectId objectId = getObjectId(ctx.getValue());

                attributeVertex = (objectId != null) ? context.getDiscoveryContext().getResolvedEntityVertex(objectId) : null;
            }
        }

        if (attributeVertex == null) {
            if(RequestContext.get().isImportInProgress()) {
                return null;
            }

            throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, (ctx.getValue() == null ? null : ctx.getValue().toString()));
        }

        AtlasType type = typeRegistry.getType(AtlasGraphUtilsV2.getTypeName(entityVertex));

        if (type instanceof AtlasEntityType) {
            AtlasEntityType entityType = (AtlasEntityType) type;
            AtlasAttribute  attribute     = ctx.getAttribute();
            String          attributeName = attribute.getName();

            // use relationship to create/update edges
            if (entityType.hasRelationshipAttribute(attributeName)) {
                Map<String, Object> relationshipAttributes = getRelationshipAttributes(ctx.getValue());

                if (ctx.getCurrentEdge() != null) {
                    ret = updateRelationship(ctx.getCurrentEdge(), entityVertex, attributeVertex, attribute.getRelationshipEdgeDirection(), relationshipAttributes);
                } else {
                    String      relationshipName = attribute.getRelationshipName();
                    AtlasVertex fromVertex;
                    AtlasVertex toVertex;

                    if (StringUtils.isEmpty(relationshipName)) {
                        relationshipName = graphHelper.getRelationshipTypeName(entityVertex, entityType, attributeName);
                    }

                    if (attribute.getRelationshipEdgeDirection() == IN) {
                        fromVertex = attributeVertex;
                        toVertex   = entityVertex;

                    } else {
                        fromVertex = entityVertex;
                        toVertex   = attributeVertex;
                    }

                    ret = getOrCreateRelationship(fromVertex, toVertex, relationshipName, relationshipAttributes);

                    boolean isCreated = GraphHelper.getCreatedTime(ret) == RequestContext.get().getRequestTime();

                    if (isCreated) {
                        // if relationship did not exist before and new relationship was created
                        // record entity update on both relationship vertices
                        recordEntityUpdate(attributeVertex);
                    }

                    // for import use the relationship guid provided
                    if (RequestContext.get().isImportInProgress()) {
                        String relationshipGuid = getRelationshipGuid(ctx.getValue());

                        if(!StringUtils.isEmpty(relationshipGuid)) {
                            AtlasGraphUtilsV2.setEncodedProperty(ret, RELATIONSHIP_GUID_PROPERTY_KEY, relationshipGuid);
                        }
                    }
                }
            } else {
                // use legacy way to create/update edges
                if (WARN_ON_NO_RELATIONSHIP || LOG.isDebugEnabled()) {
                    LOG.warn("No RelationshipDef defined between {} and {} on attribute: {}. This can lead to severe performance degradation.",
                             getTypeName(entityVertex), getTypeName(attributeVertex), attributeName);
                }

                ret = mapObjectIdValue(ctx, context);
            }

        } else {
            // if type is StructType having objectid as attribute
            ret = mapObjectIdValue(ctx, context);
        }

        setAssignedGuid(ctx.getValue(), context);

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== mapObjectIdValueUsingRelationship({})", ctx);
        }

        return ret;
    }

    private Map<String, Object> mapMapValue(AttributeMutationContext ctx, EntityMutationContext context) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> mapMapValue({})", ctx);
        }

        Map<Object, Object> newVal      = (Map<Object, Object>) ctx.getValue();
        Map<String, Object> newMap      = new HashMap<>();
        AtlasMapType        mapType     = (AtlasMapType) ctx.getAttrType();
        AtlasAttribute      attribute   = ctx.getAttribute();
        Map<String, Object> currentMap  = getMapElementsProperty(mapType, ctx.getReferringVertex(), ctx.getVertexProperty(), attribute);
        boolean             isReference = isReference(mapType.getValueType());
        boolean             isSoftReference = ctx.getAttribute().getAttributeDef().isSoftReferenced();

        boolean isNewValNull = newVal == null;

        if (isNewValNull) {
            newVal = new HashMap<>();
        }

        String propertyName = ctx.getVertexProperty();

        if (isReference) {
            for (Map.Entry<Object, Object> entry : newVal.entrySet()) {
                String    key          = entry.getKey().toString();
                AtlasEdge existingEdge = isSoftReference ? null : getEdgeIfExists(mapType, currentMap, key);

                AttributeMutationContext mapCtx =  new AttributeMutationContext(ctx.getOp(), ctx.getReferringVertex(), attribute, entry.getValue(),
                                                                                 propertyName, mapType.getValueType(), existingEdge);
                // Add/Update/Remove property value
                Object newEntry = mapCollectionElementsToVertex(mapCtx, context);

                if (!isSoftReference && newEntry instanceof AtlasEdge) {
                    AtlasEdge edge = (AtlasEdge) newEntry;

                    edge.setProperty(ATTRIBUTE_KEY_PROPERTY_KEY, key);

                    // If value type indicates this attribute is a reference, and the attribute has an inverse reference attribute,
                    // update the inverse reference value.
                    AtlasAttribute inverseRefAttribute = attribute.getInverseRefAttribute();

                    if (inverseRefAttribute != null) {
                        addInverseReference(context, inverseRefAttribute, edge, getRelationshipAttributes(ctx.getValue()));
                    }

                    updateInConsistentOwnedMapVertices(ctx, mapType, newEntry);

                    newMap.put(key, newEntry);
                }

                if (isSoftReference) {
                    newMap.put(key, newEntry);
                }
            }

            Map<String, Object> finalMap = removeUnusedMapEntries(attribute, ctx.getReferringVertex(), currentMap, newMap);
            newMap.putAll(finalMap);
        } else {
            // primitive type map
            if (isNewValNull) {
                ctx.getReferringVertex().setProperty(propertyName, null);
            } else {
                ctx.getReferringVertex().setProperty(propertyName, new HashMap<>(newVal));
            }
            newVal.forEach((key, value) -> newMap.put(key.toString(), value));
        }

        if (isSoftReference) {
            if (isNewValNull) {
                ctx.getReferringVertex().setProperty(propertyName,null);
            } else {
                ctx.getReferringVertex().setProperty(propertyName, new HashMap<>(newMap));
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== mapMapValue({})", ctx);
        }

        return newMap;
    }

    public List mapArrayValue(AttributeMutationContext ctx, EntityMutationContext context) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> mapArrayValue({})", ctx);
        }

        AtlasAttribute attribute           = ctx.getAttribute();
        List           newElements         = (List) ctx.getValue();
        AtlasArrayType arrType             = (AtlasArrayType) attribute.getAttributeType();
        AtlasType      elementType         = arrType.getElementType();
        boolean        isReference         = isReference(elementType);
        boolean        isSoftReference     = ctx.getAttribute().getAttributeDef().isSoftReferenced();
        AtlasAttribute inverseRefAttribute = attribute.getInverseRefAttribute();
        Cardinality    cardinality         = attribute.getAttributeDef().getCardinality();
        List<Object>   newElementsCreated  = new ArrayList<>();
        List<Object>   currentElements;
        boolean isNewElementsNull          = newElements == null;

        if (isReference && !isSoftReference) {
            currentElements = (List) getCollectionElementsUsingRelationship(ctx.getReferringVertex(), attribute);
        } else {
            currentElements = (List) getArrayElementsProperty(elementType, isSoftReference, ctx.getReferringVertex(), ctx.getVertexProperty());
        }

        if (isNewElementsNull) {
            newElements = new ArrayList();
        }

        if (cardinality == SET) {
            newElements = (List) newElements.stream().distinct().collect(Collectors.toList());
        }

        for (int index = 0; index < newElements.size(); index++) {
            AtlasEdge               existingEdge = (isSoftReference) ? null : getEdgeAt(currentElements, index, elementType);
            AttributeMutationContext arrCtx      = new AttributeMutationContext(ctx.getOp(), ctx.getReferringVertex(), ctx.getAttribute(), newElements.get(index),
                                                                                 ctx.getVertexProperty(), elementType, existingEdge);

            Object newEntry = mapCollectionElementsToVertex(arrCtx, context);

            if (isReference && newEntry != null && newEntry instanceof AtlasEdge && inverseRefAttribute != null) {
                // Update the inverse reference value.
                AtlasEdge newEdge = (AtlasEdge) newEntry;

                addInverseReference(context, inverseRefAttribute, newEdge, getRelationshipAttributes(ctx.getValue()));
            }

            if(newEntry != null) {
                newElementsCreated.add(newEntry);
            }
        }

        if (isReference && !isSoftReference) {
            List<AtlasEdge> additionalEdges = removeUnusedArrayEntries(attribute, (List) currentElements, (List) newElementsCreated, ctx.getReferringVertex());
            newElementsCreated.addAll(additionalEdges);
        }

        // add index to attributes of array type
       for (int index = 0; index < newElementsCreated.size(); index++) {
           Object element = newElementsCreated.get(index);

           if (element instanceof AtlasEdge) {
               AtlasGraphUtilsV2.setEncodedProperty((AtlasEdge) element, ATTRIBUTE_INDEX_PROPERTY_KEY, index);
            }
        }

        if (isNewElementsNull) {
            setArrayElementsProperty(elementType, isSoftReference, ctx.getReferringVertex(), ctx.getVertexProperty(), null);
        } else {
            setArrayElementsProperty(elementType, isSoftReference, ctx.getReferringVertex(), ctx.getVertexProperty(), newElementsCreated);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== mapArrayValue({})", ctx);
        }

        return newElementsCreated;
    }

    private AtlasEdge createVertex(AtlasStruct struct, AtlasVertex referringVertex, String edgeLabel, EntityMutationContext context) throws AtlasBaseException {
        AtlasVertex vertex = createStructVertex(struct);

        mapAttributes(struct, vertex, CREATE, context);

        try {
            //TODO - Map directly in AtlasGraphUtilsV1
            return graphHelper.getOrCreateEdge(referringVertex, vertex, edgeLabel);
        } catch (RepositoryException e) {
            throw new AtlasBaseException(AtlasErrorCode.INTERNAL_ERROR, e);
        }
    }

    private void updateVertex(AtlasStruct struct, AtlasVertex vertex, EntityMutationContext context) throws AtlasBaseException {
        mapAttributes(struct, vertex, UPDATE, context);
    }

    private Long getEntityVersion(AtlasEntity entity) {
        Long ret = entity != null ? entity.getVersion() : null;
        return (ret != null) ? ret : 0;
    }

    private String getCustomAttributesString(AtlasEntity entity) {
        String              ret              = null;
        Map<String, String> customAttributes = entity.getCustomAttributes();

        if (customAttributes != null) {
            ret = AtlasType.toJson(customAttributes);
        }

        return ret;
    }

    private AtlasStructType getStructType(String typeName) throws AtlasBaseException {
        AtlasType objType = typeRegistry.getType(typeName);

        if (!(objType instanceof AtlasStructType)) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, typeName);
        }

        return (AtlasStructType)objType;
    }

    private AtlasEntityType getEntityType(String typeName) throws AtlasBaseException {
        AtlasType objType = typeRegistry.getType(typeName);

        if (!(objType instanceof AtlasEntityType)) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, typeName);
        }

        return (AtlasEntityType)objType;
    }

    private Object mapCollectionElementsToVertex(AttributeMutationContext ctx, EntityMutationContext context) throws AtlasBaseException {
        switch(ctx.getAttrType().getTypeCategory()) {
        case PRIMITIVE:
        case ENUM:
        case MAP:
        case ARRAY:
            return ctx.getValue();

        case STRUCT:
            return mapStructValue(ctx, context);

        case OBJECT_ID_TYPE:
            AtlasEntityType instanceType = getInstanceType(ctx.getValue(), context);
            ctx.setElementType(instanceType);
            if (ctx.getAttributeDef().isSoftReferenced()) {
                return mapSoftRefValue(ctx, context);
            }

            return mapObjectIdValueUsingRelationship(ctx, context);

        default:
                throw new AtlasBaseException(AtlasErrorCode.TYPE_CATEGORY_INVALID, ctx.getAttrType().getTypeCategory().name());
        }
    }

    private static AtlasObjectId getObjectId(Object val) throws AtlasBaseException {
        AtlasObjectId ret = null;

        if (val != null) {
            if ( val instanceof  AtlasObjectId) {
                ret = ((AtlasObjectId) val);
            } else if (val instanceof Map) {
                Map map = (Map) val;

                if (map.containsKey(AtlasRelatedObjectId.KEY_RELATIONSHIP_TYPE)) {
                    ret = new AtlasRelatedObjectId(map);
                } else {
                    ret = new AtlasObjectId((Map) val);
                }

                if (!AtlasTypeUtil.isValid(ret)) {
                    throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, val.toString());
                }
            } else {
                throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, val.toString());
            }
        }

        return ret;
    }

    private static String getGuid(Object val) throws AtlasBaseException {
        if (val != null) {
            if ( val instanceof  AtlasObjectId) {
                return ((AtlasObjectId) val).getGuid();
            } else if (val instanceof Map) {
                Object guidVal = ((Map)val).get(AtlasObjectId.KEY_GUID);

                return guidVal != null ? guidVal.toString() : null;
            }
        }

        return null;
    }

    private static void setAssignedGuid(Object val, EntityMutationContext context) {
        if (val != null) {
            Map<String, String> guidAssignements = context.getGuidAssignments();

            if (val instanceof AtlasObjectId) {
                AtlasObjectId objId        = (AtlasObjectId) val;
                String        guid         = objId.getGuid();
                String        assignedGuid = null;

                if (StringUtils.isNotEmpty(guid)) {
                    if (!AtlasTypeUtil.isAssignedGuid(guid) && MapUtils.isNotEmpty(guidAssignements)) {
                        assignedGuid = guidAssignements.get(guid);
                    }
                } else {
                    AtlasVertex vertex = context.getDiscoveryContext().getResolvedEntityVertex(objId);

                    if (vertex != null) {
                        assignedGuid = GraphHelper.getGuid(vertex);
                    }
                }

                if (StringUtils.isNotEmpty(assignedGuid)) {
                    RequestContext.get().recordEntityGuidUpdate(objId, guid);

                    objId.setGuid(assignedGuid);
                }
            } else if (val instanceof Map) {
                Map    mapObjId     = (Map) val;
                Object guidVal      = mapObjId.get(AtlasObjectId.KEY_GUID);
                String guid         = guidVal != null ? guidVal.toString() : null;
                String assignedGuid = null;

                if (StringUtils.isNotEmpty(guid) ) {
                    if (!AtlasTypeUtil.isAssignedGuid(guid) && MapUtils.isNotEmpty(guidAssignements)) {
                        assignedGuid = guidAssignements.get(guid);
                    }
                } else {
                    AtlasVertex vertex = context.getDiscoveryContext().getResolvedEntityVertex(new AtlasObjectId(mapObjId));

                    if (vertex != null) {
                        assignedGuid = GraphHelper.getGuid(vertex);
                    }
                }

                if (StringUtils.isNotEmpty(assignedGuid)) {
                    RequestContext.get().recordEntityGuidUpdate(mapObjId, guid);

                    mapObjId.put(AtlasObjectId.KEY_GUID, assignedGuid);
                }
            }
        }
    }

    private static Map<String, Object> getRelationshipAttributes(Object val) throws AtlasBaseException {
        if (val instanceof AtlasRelatedObjectId) {
            AtlasStruct relationshipStruct = ((AtlasRelatedObjectId) val).getRelationshipAttributes();

            return (relationshipStruct != null) ? relationshipStruct.getAttributes() : null;
        } else if (val instanceof Map) {
            Object relationshipStruct = ((Map) val).get(KEY_RELATIONSHIP_ATTRIBUTES);

            if (relationshipStruct instanceof Map) {
                return AtlasTypeUtil.toStructAttributes(((Map) relationshipStruct));
            }
        }

        return null;
    }

    private static String getRelationshipGuid(Object val) throws AtlasBaseException {
        if (val instanceof AtlasRelatedObjectId) {
            return ((AtlasRelatedObjectId) val).getRelationshipGuid();
        } else if (val instanceof Map) {
            Object relationshipGuidVal = ((Map) val).get(AtlasRelatedObjectId.KEY_RELATIONSHIP_GUID);

            return relationshipGuidVal != null ? relationshipGuidVal.toString() : null;
        }

        return null;
    }

    private AtlasEntityType getInstanceType(Object val, EntityMutationContext context) throws AtlasBaseException {
        AtlasEntityType ret = null;

        if (val != null) {
            String typeName = null;
            String guid     = null;

            if (val instanceof AtlasObjectId) {
                AtlasObjectId objId = (AtlasObjectId) val;

                typeName = objId.getTypeName();
                guid     = objId.getGuid();
            } else if (val instanceof Map) {
                Map map = (Map) val;

                Object typeNameVal = map.get(AtlasObjectId.KEY_TYPENAME);
                Object guidVal     = map.get(AtlasObjectId.KEY_GUID);

                if (typeNameVal != null) {
                    typeName = typeNameVal.toString();
                }

                if (guidVal != null) {
                    guid = guidVal.toString();
                }
            }

            if (typeName == null) {
                if (guid != null) {
                    ret = context.getType(guid);

                    if (ret == null) {
                        AtlasVertex vertex = context.getDiscoveryContext().getResolvedEntityVertex(guid);

                        if (vertex != null) {
                            typeName = AtlasGraphUtilsV2.getTypeName(vertex);
                        }
                    }
                }
            }

            if (ret == null && typeName != null) {
                ret = typeRegistry.getEntityTypeByName(typeName);
            }

            if (ret == null) {
                throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, val.toString());
            }
        }

        return ret;
    }

    //Remove unused entries for reference map
    private Map<String, Object> removeUnusedMapEntries(AtlasAttribute attribute, AtlasVertex vertex, Map<String, Object> currentMap,
                                                       Map<String, Object> newMap) throws AtlasBaseException {
        Map<String, Object> additionalMap = new HashMap<>();
        AtlasMapType        mapType       = (AtlasMapType) attribute.getAttributeType();

        for (String currentKey : currentMap.keySet()) {
            //Delete the edge reference if its not part of new edges created/updated
            AtlasEdge currentEdge = (AtlasEdge) currentMap.get(currentKey);

            if (!newMap.values().contains(currentEdge)) {
                boolean deleted = deleteDelegate.getHandler().deleteEdgeReference(currentEdge, mapType.getValueType().getTypeCategory(), attribute.isOwnedRef(), true, vertex);

                if (!deleted) {
                    additionalMap.put(currentKey, currentEdge);
                }
            }
        }

        return additionalMap;
    }

    private static AtlasEdge getEdgeIfExists(AtlasMapType mapType, Map<String, Object> currentMap, String keyStr) {
        AtlasEdge ret = null;

        if (isReference(mapType.getValueType())) {
            Object val = currentMap.get(keyStr);

            if (val != null) {
                ret = (AtlasEdge) val;
            }
        }

        return ret;
    }

    private AtlasEdge updateEdge(AtlasAttributeDef attributeDef, Object value, AtlasEdge currentEdge, final AtlasVertex entityVertex) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Updating entity reference {} for reference attribute {}",  attributeDef.getName());
        }

        AtlasVertex currentVertex   = currentEdge.getInVertex();
        String      currentEntityId = getIdFromVertex(currentVertex);
        String      newEntityId     = getIdFromVertex(entityVertex);
        AtlasEdge   newEdge         = currentEdge;

        if (!currentEntityId.equals(newEntityId) && entityVertex != null) {
            try {
                newEdge = graphHelper.getOrCreateEdge(currentEdge.getOutVertex(), entityVertex, currentEdge.getLabel());
            } catch (RepositoryException e) {
                throw new AtlasBaseException(AtlasErrorCode.INTERNAL_ERROR, e);
            }
        }

        return newEdge;
    }


    private AtlasEdge updateRelationship(AtlasEdge currentEdge, final AtlasVertex parentEntityVertex, final AtlasVertex newEntityVertex,
                                         AtlasRelationshipEdgeDirection edgeDirection,  Map<String, Object> relationshipAttributes)
                                         throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Updating entity reference using relationship {} for reference attribute {}", getTypeName(newEntityVertex));
        }

        // Max's manager updated from Jane to Julius (Max.manager --> Jane.subordinates)
        // manager attribute (OUT direction), current manager vertex (Jane) (IN vertex)

        // Max's mentor updated from John to Jane (John.mentee --> Max.mentor)
        // mentor attribute (IN direction), current mentee vertex (John) (OUT vertex)
        String currentEntityId;

        if (edgeDirection == IN) {
            currentEntityId = getIdFromOutVertex(currentEdge);
        } else if (edgeDirection == OUT) {
            currentEntityId = getIdFromInVertex(currentEdge);
        } else {
            currentEntityId = getIdFromBothVertex(currentEdge, parentEntityVertex);
        }

        String    newEntityId = getIdFromVertex(newEntityVertex);
        AtlasEdge ret         = currentEdge;

        if (!currentEntityId.equals(newEntityId)) {
            // create a new relationship edge to the new attribute vertex from the instance
            String relationshipName = AtlasGraphUtilsV2.getTypeName(currentEdge);

            if (relationshipName == null) {
                relationshipName = currentEdge.getLabel();
            }

            if (edgeDirection == IN) {
                ret = getOrCreateRelationship(newEntityVertex, currentEdge.getInVertex(), relationshipName, relationshipAttributes);

            } else if (edgeDirection == OUT) {
                ret = getOrCreateRelationship(currentEdge.getOutVertex(), newEntityVertex, relationshipName, relationshipAttributes);
            } else {
                ret = getOrCreateRelationship(newEntityVertex, parentEntityVertex, relationshipName, relationshipAttributes);
            }

            //record entity update on new relationship vertex
            recordEntityUpdate(newEntityVertex);
        }

        return ret;
    }

    public static List<Object> getArrayElementsProperty(AtlasType elementType, boolean isSoftReference, AtlasVertex vertex, String vertexPropertyName) {
        if (!isSoftReference && isReference(elementType)) {
            return (List)vertex.getListProperty(vertexPropertyName, AtlasEdge.class);
        }
        else {
            return (List)vertex.getListProperty(vertexPropertyName);
        }
    }

    private AtlasEdge getEdgeAt(List<Object> currentElements, int index, AtlasType elemType) {
        AtlasEdge ret = null;

        if (isReference(elemType)) {
            if (currentElements != null && index < currentElements.size()) {
                ret = (AtlasEdge) currentElements.get(index);
            }
        }

        return ret;
    }

    //Removes unused edges from the old collection, compared to the new collection

    private List<AtlasEdge> removeUnusedArrayEntries(AtlasAttribute attribute, List<AtlasEdge> currentEntries, List<AtlasEdge> newEntries, AtlasVertex entityVertex) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(currentEntries)) {
            AtlasType entryType = ((AtlasArrayType) attribute.getAttributeType()).getElementType();

            if (isReference(entryType)) {
                Collection<AtlasEdge> edgesToRemove = CollectionUtils.subtract(currentEntries, newEntries);

                if (CollectionUtils.isNotEmpty(edgesToRemove)) {
                    List<AtlasEdge> additionalElements = new ArrayList<>();

                    for (AtlasEdge edge : edgesToRemove) {
                        boolean deleted = deleteDelegate.getHandler().deleteEdgeReference(edge, entryType.getTypeCategory(), attribute.isOwnedRef(),
                                                                             true, attribute.getRelationshipEdgeDirection(), entityVertex);

                        if (!deleted) {
                            additionalElements.add(edge);
                        }
                    }

                    return additionalElements;
                }
            }
        }

        return Collections.emptyList();
    }
    private void setArrayElementsProperty(AtlasType elementType, boolean isSoftReference, AtlasVertex vertex, String vertexPropertyName, List<Object> values) {
        if (!isReference(elementType) || isSoftReference) {
            AtlasGraphUtilsV2.setEncodedProperty(vertex, vertexPropertyName, values);
        }
    }

    private AtlasEntityHeader constructHeader(AtlasEntity entity, final AtlasEntityType type, AtlasVertex vertex) {
        AtlasEntityHeader header = new AtlasEntityHeader(entity.getTypeName());

        header.setGuid(getIdFromVertex(vertex));
        header.setStatus(entity.getStatus());
        header.setIsIncomplete(entity.getIsIncomplete());

        for (AtlasAttribute attribute : type.getUniqAttributes().values()) {
            header.setAttribute(attribute.getName(), entity.getAttribute(attribute.getName()));
        }

        return header;
    }

    private void updateInConsistentOwnedMapVertices(AttributeMutationContext ctx, AtlasMapType mapType, Object val) {
        if (mapType.getValueType().getTypeCategory() == TypeCategory.OBJECT_ID_TYPE && !ctx.getAttributeDef().isSoftReferenced()) {
            AtlasEdge edge = (AtlasEdge) val;

            if (ctx.getAttribute().isOwnedRef() && getStatus(edge) == DELETED && getStatus(edge.getInVertex()) == DELETED) {

                //Resurrect the vertex and edge to ACTIVE state
                AtlasGraphUtilsV2.setEncodedProperty(edge, STATE_PROPERTY_KEY, ACTIVE.name());
                AtlasGraphUtilsV2.setEncodedProperty(edge.getInVertex(), STATE_PROPERTY_KEY, ACTIVE.name());
            }
        }
    }

    public void addClassifications(final EntityMutationContext context, String guid, List<AtlasClassification> classifications) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(classifications)) {
            MetricRecorder metric = RequestContext.get().startMetricRecord("addClassifications");

            final AtlasVertex                              entityVertex          = context.getVertex(guid);
            final AtlasEntityType                          entityType            = context.getType(guid);
            List<AtlasVertex>                              entitiesToPropagateTo = null;
            Map<AtlasClassification, HashSet<AtlasVertex>> addedClassifications  = new HashMap<>();
            List<AtlasClassification>                      addClassifications    = new ArrayList<>(classifications.size());

            for (AtlasClassification c : classifications) {
                AtlasClassification classification      = new AtlasClassification(c);
                String              classificationName  = classification.getTypeName();
                Boolean             propagateTags       = classification.isPropagate();
                Boolean             removePropagations  = classification.getRemovePropagationsOnEntityDelete();

                if (propagateTags != null && propagateTags &&
                        classification.getEntityGuid() != null &&
                        !StringUtils.equals(classification.getEntityGuid(), guid)) {
                    continue;
                }

                if (propagateTags == null) {
                    RequestContext reqContext = RequestContext.get();

                    if(reqContext.isImportInProgress() || reqContext.isInNotificationProcessing()) {
                        propagateTags = false;
                        classification.setPropagate(propagateTags);
                    } else {
                        propagateTags = true;
                    }
                }

                if (removePropagations == null) {
                    removePropagations = getDefaultRemovePropagations();

                    classification.setRemovePropagationsOnEntityDelete(removePropagations);
                }

                // set associated entity id to classification
                if (classification.getEntityGuid() == null) {
                    classification.setEntityGuid(guid);
                }

                // set associated entity status to classification
                if (classification.getEntityStatus() == null) {
                    classification.setEntityStatus(ACTIVE);
                }

                // ignore propagated classifications

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Adding classification [{}] to [{}] using edge label: [{}]", classificationName, entityType.getTypeName(), getTraitLabel(classificationName));
                }

                addToClassificationNames(entityVertex, classificationName);

                // add a new AtlasVertex for the struct or trait instance
                AtlasVertex classificationVertex = createClassificationVertex(classification);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("created vertex {} for trait {}", string(classificationVertex), classificationName);
                }

                // add the attributes for the trait instance
                mapClassification(EntityOperation.CREATE, context, classification, entityType, entityVertex, classificationVertex);
                updateModificationMetadata(entityVertex);
                if(addedClassifications.get(classification) == null) {
                    addedClassifications.put(classification, new HashSet<>());
                }
                //Add current Vertex to be notified
                addedClassifications.get(classification).add(entityVertex);

                if (propagateTags) {
                    // compute propagatedEntityVertices only once
                    if (entitiesToPropagateTo == null) {
                        entitiesToPropagateTo = entityRetriever.getImpactedVerticesV2(entityVertex);
                    }

                    if (CollectionUtils.isNotEmpty(entitiesToPropagateTo)) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Propagating tag: [{}][{}] to {}", classificationName, entityType.getTypeName(), getTypeNames(entitiesToPropagateTo));
                        }

                        List<AtlasVertex> entitiesPropagatedTo = deleteDelegate.getHandler().addTagPropagation(classificationVertex, entitiesToPropagateTo);

                        if (CollectionUtils.isNotEmpty(entitiesPropagatedTo)) {
                            addedClassifications.get(classification).addAll(entitiesPropagatedTo);
                        }
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(" --> Not propagating classification: [{}][{}] - no entities found to propagate to.", getTypeName(classificationVertex), entityType.getTypeName());
                        }
                    }
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(" --> Not propagating classification: [{}][{}] - propagation is disabled.", getTypeName(classificationVertex), entityType.getTypeName());
                    }
                }

                addClassifications.add(classification);
            }

            // notify listeners on classification addition
            List<AtlasVertex> notificationVertices = new ArrayList<AtlasVertex>() {{ add(entityVertex); }};

            if (CollectionUtils.isNotEmpty(entitiesToPropagateTo)) {
                notificationVertices.addAll(entitiesToPropagateTo);
            }

            for (AtlasClassification classification : addedClassifications.keySet()) {
                Set<AtlasVertex>  vertices           = addedClassifications.get(classification);
                List<AtlasEntity> propagatedEntities = updateClassificationText(classification, vertices);

                if (entityChangeNotifier != null) {
                    entityChangeNotifier.onClassificationsAddedToEntities(propagatedEntities, Collections.singletonList(classification));
                }
            }

            RequestContext.get().endMetricRecord(metric);
        }
    }

    public void deleteClassification(String entityGuid, String classificationName, String associatedEntityGuid) throws AtlasBaseException {
        if (StringUtils.isEmpty(associatedEntityGuid) || associatedEntityGuid.equals(entityGuid)) {
            deleteClassification(entityGuid, classificationName);
        } else {
            deletePropagatedClassification(entityGuid, classificationName, associatedEntityGuid);
        }
    }

    private void deletePropagatedClassification(String entityGuid, String classificationName, String associatedEntityGuid) throws AtlasBaseException {
        if (StringUtils.isEmpty(classificationName)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_CLASSIFICATION_PARAMS, "delete", entityGuid);
        }

        AtlasVertex entityVertex = AtlasGraphUtilsV2.findByGuid(entityGuid);

        if (entityVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, entityGuid);
        }

        deleteDelegate.getHandler().deletePropagatedClassification(entityVertex, classificationName, associatedEntityGuid);
    }

    public void deleteClassification(String entityGuid, String classificationName) throws AtlasBaseException {
        if (StringUtils.isEmpty(classificationName)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_CLASSIFICATION_PARAMS, "delete", entityGuid);
        }

        AtlasVertex entityVertex = AtlasGraphUtilsV2.findByGuid(entityGuid);

        if (entityVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, entityGuid);
        }

        List<String> traitNames = getTraitNames(entityVertex);

        if (CollectionUtils.isEmpty(traitNames)) {
            throw new AtlasBaseException(AtlasErrorCode.NO_CLASSIFICATIONS_FOUND_FOR_ENTITY, entityGuid);
        }

        validateClassificationExists(traitNames, classificationName);

        Map<AtlasVertex, List<AtlasClassification>> removedClassifications = new HashMap<>();

        AtlasVertex         classificationVertex = getClassificationVertex(entityVertex, classificationName);
        AtlasClassification classification       = entityRetriever.toAtlasClassification(classificationVertex);

        if (classification == null) {
            throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_FOUND, classificationName);
        }

        // remove classification from propagated entities if propagation is turned on
        if (isPropagationEnabled(classificationVertex)) {
            List<AtlasVertex> propagatedEntityVertices = deleteDelegate.getHandler().removeTagPropagation(classificationVertex);

            // add propagated entities and deleted classification details to removeClassifications map
            if (CollectionUtils.isNotEmpty(propagatedEntityVertices)) {
                for (AtlasVertex propagatedEntityVertex : propagatedEntityVertices) {
                    List<AtlasClassification> classifications = removedClassifications.get(propagatedEntityVertex);

                    if (classifications == null) {
                        classifications = new ArrayList<>();

                        removedClassifications.put(propagatedEntityVertex, classifications);
                    }

                    classifications.add(classification);
                }
            }
        }

        // add associated entity and deleted classification details to removeClassifications map
        List<AtlasClassification> classifications = removedClassifications.get(entityVertex);

        if (classifications == null) {
            classifications = new ArrayList<>();

            removedClassifications.put(entityVertex, classifications);
        }

        classifications.add(classification);

        // remove classifications from associated entity
        if (LOG.isDebugEnabled()) {
            LOG.debug("Removing classification: [{}] from: [{}][{}] with edge label: [{}]", classificationName,
                    getTypeName(entityVertex), entityGuid, CLASSIFICATION_LABEL);
        }

        AtlasEdge edge = getClassificationEdge(entityVertex, classificationVertex);

        deleteDelegate.getHandler().deleteEdgeReference(edge, CLASSIFICATION, false, true, entityVertex);

        traitNames.remove(classificationName);

        setClassificationNames(entityVertex, traitNames);

        updateModificationMetadata(entityVertex);

        for (Map.Entry<AtlasVertex, List<AtlasClassification>> entry : removedClassifications.entrySet()) {
            AtlasEntity entity = updateClassificationText(entry.getKey());

            List<AtlasClassification> deletedClassificationNames = entry.getValue();

            if (entityChangeNotifier != null) {
                entityChangeNotifier.onClassificationDeletedFromEntity(entity, deletedClassificationNames);
            }
        }
    }

    private AtlasEntity updateClassificationText(AtlasVertex vertex) throws AtlasBaseException {
        String guid        = GraphHelper.getGuid(vertex);
        AtlasEntity entity = instanceConverter.getAndCacheEntity(guid, ENTITY_CHANGE_NOTIFY_IGNORE_RELATIONSHIP_ATTRIBUTES);

        vertex.setProperty(CLASSIFICATION_TEXT_KEY, fullTextMapperV2.getClassificationTextForEntity(entity));
        return entity;
    }

    public void updateClassificationTextAndNames(AtlasVertex vertex) throws AtlasBaseException {
        if(CollectionUtils.isEmpty(vertex.getPropertyValues(Constants.TRAIT_NAMES_PROPERTY_KEY, String.class)) &&
                CollectionUtils.isEmpty(vertex.getPropertyValues(Constants.PROPAGATED_TRAIT_NAMES_PROPERTY_KEY, String.class))) {
            return;
        }

        String guid = GraphHelper.getGuid(vertex);
        AtlasEntity entity = instanceConverter.getAndCacheEntity(guid, ENTITY_CHANGE_NOTIFY_IGNORE_RELATIONSHIP_ATTRIBUTES);
        List<String> classificationNames = new ArrayList<>();
        List<String> propagatedClassificationNames = new ArrayList<>();

        for (AtlasClassification classification : entity.getClassifications()) {
            if (isPropagatedClassification(classification, guid)) {
                propagatedClassificationNames.add(classification.getTypeName());
            } else {
                classificationNames.add(classification.getTypeName());
            }
        }

        vertex.setProperty(CLASSIFICATION_NAMES_KEY, getDelimitedClassificationNames(classificationNames));
        vertex.setProperty(PROPAGATED_CLASSIFICATION_NAMES_KEY, getDelimitedClassificationNames(propagatedClassificationNames));
        vertex.setProperty(CLASSIFICATION_TEXT_KEY, fullTextMapperV2.getClassificationTextForEntity(entity));
    }

    private boolean isPropagatedClassification(AtlasClassification classification, String guid) {
        String classificationEntityGuid = classification.getEntityGuid();

        return StringUtils.isNotEmpty(classificationEntityGuid) && !StringUtils.equals(classificationEntityGuid, guid);
    }

    private void addToClassificationNames(AtlasVertex entityVertex, String classificationName) {
        AtlasGraphUtilsV2.addEncodedProperty(entityVertex, TRAIT_NAMES_PROPERTY_KEY, classificationName);

        String clsNames = entityVertex.getProperty(CLASSIFICATION_NAMES_KEY, String.class);

        clsNames = StringUtils.isEmpty(clsNames) ? CLASSIFICATION_NAME_DELIMITER + classificationName : clsNames + classificationName;

        clsNames = clsNames + CLASSIFICATION_NAME_DELIMITER;

        entityVertex.setProperty(CLASSIFICATION_NAMES_KEY, clsNames);
    }

    private void setClassificationNames(AtlasVertex entityVertex, List<String> traitNames) {
        if (entityVertex != null) {
            entityVertex.removeProperty(TRAIT_NAMES_PROPERTY_KEY);
            entityVertex.removeProperty(CLASSIFICATION_NAMES_KEY);

            for (String traitName : traitNames) {
                AtlasGraphUtilsV2.addEncodedProperty(entityVertex, TRAIT_NAMES_PROPERTY_KEY, traitName);
            }

            String clsNames = StringUtils.join(traitNames, CLASSIFICATION_NAME_DELIMITER);

            clsNames = StringUtils.isEmpty(clsNames) ? clsNames : CLASSIFICATION_NAME_DELIMITER + clsNames + CLASSIFICATION_NAME_DELIMITER;

            entityVertex.setProperty(CLASSIFICATION_NAMES_KEY, clsNames);
        }
    }

    public void updateClassifications(EntityMutationContext context, String guid, List<AtlasClassification> classifications) throws AtlasBaseException {
        if (CollectionUtils.isEmpty(classifications)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_CLASSIFICATION_PARAMS, "update", guid);
        }

        AtlasVertex entityVertex = AtlasGraphUtilsV2.findByGuid(guid);

        if (entityVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        AtlasPerfTracer perf = null;

        if (AtlasPerfTracer.isPerfTraceEnabled(PERF_LOG)) {
            perf = AtlasPerfTracer.getPerfTracer(PERF_LOG, "EntityGraphMapper.updateClassifications");
        }

        String                    entityTypeName         = AtlasGraphUtilsV2.getTypeName(entityVertex);
        AtlasEntityType           entityType             = typeRegistry.getEntityTypeByName(entityTypeName);
        List<AtlasClassification> updatedClassifications = new ArrayList<>();
        List<AtlasVertex>         entitiesToPropagateTo  = new ArrayList<>();
        Set<AtlasVertex>          notificationVertices   = new HashSet<AtlasVertex>() {{ add(entityVertex); }};

        Map<AtlasVertex, List<AtlasClassification>> addedPropagations   = null;
        Map<AtlasClassification, List<AtlasVertex>> removedPropagations = new HashMap<>();

        for (AtlasClassification classification : classifications) {
            String classificationName       = classification.getTypeName();
            String classificationEntityGuid = classification.getEntityGuid();

            if (StringUtils.isEmpty(classificationEntityGuid)) {
                classification.setEntityGuid(guid);
            }

            if (StringUtils.isNotEmpty(classificationEntityGuid) && !StringUtils.equalsIgnoreCase(guid, classificationEntityGuid)) {
                throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_UPDATE_FROM_PROPAGATED_ENTITY, classificationName);
            }

            AtlasVertex classificationVertex = getClassificationVertex(entityVertex, classificationName);

            if (classificationVertex == null) {
                throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_ASSOCIATED_WITH_ENTITY, classificationName);
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Updating classification {} for entity {}", classification, guid);
            }

            AtlasClassification currentClassification = entityRetriever.toAtlasClassification(classificationVertex);

            if (currentClassification == null) {
                continue;
            }

            validateAndNormalizeForUpdate(classification);

            boolean isClassificationUpdated = false;

            // check for attribute update
            Map<String, Object> updatedAttributes = classification.getAttributes();

            if (MapUtils.isNotEmpty(updatedAttributes)) {
                for (String attributeName : updatedAttributes.keySet()) {
                    currentClassification.setAttribute(attributeName, updatedAttributes.get(attributeName));
                }

                isClassificationUpdated = true;
            }

            // check for validity period update
            List<TimeBoundary> currentValidityPeriods = currentClassification.getValidityPeriods();
            List<TimeBoundary> updatedValidityPeriods = classification.getValidityPeriods();

            if (!Objects.equals(currentValidityPeriods, updatedValidityPeriods)) {
                currentClassification.setValidityPeriods(updatedValidityPeriods);

                isClassificationUpdated = true;
            }

            // check for removePropagationsOnEntityDelete update
            Boolean currentRemovePropagations = currentClassification.getRemovePropagationsOnEntityDelete();
            Boolean updatedRemovePropagations = classification.getRemovePropagationsOnEntityDelete();

            if (updatedRemovePropagations != null && (updatedRemovePropagations != currentRemovePropagations)) {
                AtlasGraphUtilsV2.setEncodedProperty(classificationVertex, CLASSIFICATION_VERTEX_REMOVE_PROPAGATIONS_KEY, updatedRemovePropagations);

                isClassificationUpdated = true;
            }

            if (isClassificationUpdated) {
                List<AtlasVertex> propagatedEntityVertices = graphHelper.getAllPropagatedEntityVertices(classificationVertex);

                notificationVertices.addAll(propagatedEntityVertices);
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("updating vertex {} for trait {}", string(classificationVertex), classificationName);
            }

            mapClassification(EntityOperation.UPDATE, context, classification, entityType, entityVertex, classificationVertex);
            updateModificationMetadata(entityVertex);

            // handle update of 'propagate' flag
            Boolean currentTagPropagation = currentClassification.isPropagate();
            Boolean updatedTagPropagation = classification.isPropagate();

            // compute propagatedEntityVertices once and use it for subsequent iterations and notifications
            if (updatedTagPropagation != null && currentTagPropagation != updatedTagPropagation) {
                if (updatedTagPropagation) {
                    if (CollectionUtils.isEmpty(entitiesToPropagateTo)) {
                        entitiesToPropagateTo = entityRetriever.getImpactedVerticesV2(entityVertex, null, classificationVertex.getIdForDisplay());
                    }

                    if (CollectionUtils.isNotEmpty(entitiesToPropagateTo)) {
                        if (addedPropagations == null) {
                            addedPropagations = new HashMap<>(entitiesToPropagateTo.size());

                            for (AtlasVertex entityToPropagateTo : entitiesToPropagateTo) {
                                addedPropagations.put(entityToPropagateTo, new ArrayList<>());
                            }
                        }

                        List<AtlasVertex> entitiesPropagatedTo = deleteDelegate.getHandler().addTagPropagation(classificationVertex, entitiesToPropagateTo);

                        if (entitiesPropagatedTo != null) {
                            for (AtlasVertex entityPropagatedTo : entitiesPropagatedTo) {
                                addedPropagations.get(entityPropagatedTo).add(classification);
                            }
                        }
                    }
                } else {
                    List<AtlasVertex> impactedVertices = deleteDelegate.getHandler().removeTagPropagation(classificationVertex);

                    if (CollectionUtils.isNotEmpty(impactedVertices)) {
                        /*
                            removedPropagations is a HashMap of entity against list of classifications i.e. for each entity 1 entry in the map.
                            Maintaining classification wise entity list lets us send the audit request in bulk,
                            since 1 classification is applied to many entities (including the child entities).
                            Eg. If a classification is being propagated to 1000 entities, its edge count would be 2000, as per removedPropagations map
                            we would have 2000 entries and value would always be 1 classification wrapped in a list.
                            By this rearrangement we maintain an entity list against each classification, as of now its entry size would be 1 (as per request from UI)
                            instead of 2000. Moreover this allows us to send audit request classification wise instead of separate requests for each entities.
                            This reduces audit calls from 2000 to 1.
                         */
                        removedPropagations.put(classification, impactedVertices);
                    }
                }
            }

            updatedClassifications.add(currentClassification);
        }

        if (CollectionUtils.isNotEmpty(entitiesToPropagateTo)) {
            notificationVertices.addAll(entitiesToPropagateTo);
        }

        if (entityChangeNotifier != null) {
            for (AtlasVertex vertex : notificationVertices) {
                String entityGuid = GraphHelper.getGuid(vertex);
                AtlasEntity entity = instanceConverter.getAndCacheEntity(entityGuid, ENTITY_CHANGE_NOTIFY_IGNORE_RELATIONSHIP_ATTRIBUTES);

                if (isActive(entity)) {
                    vertex.setProperty(CLASSIFICATION_TEXT_KEY, fullTextMapperV2.getClassificationTextForEntity(entity));
                    entityChangeNotifier.onClassificationUpdatedToEntity(entity, updatedClassifications);
                }
            }
        }

        if (entityChangeNotifier != null && MapUtils.isNotEmpty(removedPropagations)) {
            for (AtlasClassification classification : removedPropagations.keySet()) {
                List<AtlasVertex> propagatedVertices = removedPropagations.get(classification);
                List<AtlasEntity> propagatedEntities = updateClassificationText(classification, propagatedVertices);

                //Sending audit request for all entities at once
                entityChangeNotifier.onClassificationsDeletedFromEntities(propagatedEntities, Collections.singletonList(classification));
            }
        }

        AtlasPerfTracer.log(perf);
    }

    private AtlasEdge mapClassification(EntityOperation operation,  final EntityMutationContext context, AtlasClassification classification,
                                        AtlasEntityType entityType, AtlasVertex parentInstanceVertex, AtlasVertex traitInstanceVertex)
                                        throws AtlasBaseException {
        if (classification.getValidityPeriods() != null) {
            String strValidityPeriods = AtlasJson.toJson(classification.getValidityPeriods());

            AtlasGraphUtilsV2.setEncodedProperty(traitInstanceVertex, CLASSIFICATION_VALIDITY_PERIODS_KEY, strValidityPeriods);
        } else {
            // if 'null', don't update existing value in the classification
        }

        if (classification.isPropagate() != null) {
            AtlasGraphUtilsV2.setEncodedProperty(traitInstanceVertex, CLASSIFICATION_VERTEX_PROPAGATE_KEY, classification.isPropagate());
        }

        if (classification.getRemovePropagationsOnEntityDelete() != null) {
            AtlasGraphUtilsV2.setEncodedProperty(traitInstanceVertex, CLASSIFICATION_VERTEX_REMOVE_PROPAGATIONS_KEY, classification.getRemovePropagationsOnEntityDelete());
        }

        // map all the attributes to this newly created AtlasVertex
        mapAttributes(classification, traitInstanceVertex, operation, context);

        AtlasEdge ret = getClassificationEdge(parentInstanceVertex, traitInstanceVertex);

        if (ret == null) {
            ret = graphHelper.addClassificationEdge(parentInstanceVertex, traitInstanceVertex, false);
        }

        return ret;
    }

    public void deleteClassifications(String guid) throws AtlasBaseException {
        AtlasVertex instanceVertex = AtlasGraphUtilsV2.findByGuid(guid);

        if (instanceVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        List<String> traitNames = getTraitNames(instanceVertex);

        if (CollectionUtils.isNotEmpty(traitNames)) {
            for (String traitName : traitNames) {
                deleteClassification(guid, traitName);
            }
        }
    }

    private void validateClassificationExists(List<String> existingClassifications, List<String> suppliedClassifications) throws AtlasBaseException {
        Set<String> existingNames = new HashSet<>(existingClassifications);
        for (String classificationName : suppliedClassifications) {
            if (!existingNames.contains(classificationName)) {
                throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_ASSOCIATED_WITH_ENTITY, classificationName);
            }
        }
    }

    private void validateClassificationExists(List<String> existingClassifications, String suppliedClassificationName) throws AtlasBaseException {
        if (!existingClassifications.contains(suppliedClassificationName)) {
            throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_ASSOCIATED_WITH_ENTITY, suppliedClassificationName);
        }
    }

    private AtlasEdge getOrCreateRelationship(AtlasVertex end1Vertex, AtlasVertex end2Vertex, String relationshipName,
                                              Map<String, Object> relationshipAttributes) throws AtlasBaseException {
        return relationshipStore.getOrCreate(end1Vertex, end2Vertex, new AtlasRelationship(relationshipName, relationshipAttributes));
    }

    private void recordEntityUpdate(AtlasVertex vertex) throws AtlasBaseException {
        if (vertex != null) {
            RequestContext req = RequestContext.get();

            if (!req.isUpdatedEntity(GraphHelper.getGuid(vertex))) {
                updateModificationMetadata(vertex);

                req.recordEntityUpdate(entityRetriever.toAtlasEntityHeader(vertex));
            }
        }
    }

    private String getIdFromInVertex(AtlasEdge edge) {
        return getIdFromVertex(edge.getInVertex());
    }

    private String getIdFromOutVertex(AtlasEdge edge) {
        return getIdFromVertex(edge.getOutVertex());
    }

    private String getIdFromBothVertex(AtlasEdge currentEdge, AtlasVertex parentEntityVertex) {
        String parentEntityId  = getIdFromVertex(parentEntityVertex);
        String currentEntityId = getIdFromVertex(currentEdge.getInVertex());

        if (StringUtils.equals(currentEntityId, parentEntityId)) {
            currentEntityId = getIdFromOutVertex(currentEdge);
        }


        return currentEntityId;
    }

    public void validateAndNormalizeForUpdate(AtlasClassification classification) throws AtlasBaseException {
        AtlasClassificationType type = typeRegistry.getClassificationTypeByName(classification.getTypeName());

        if (type == null) {
            throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_FOUND, classification.getTypeName());
        }

        List<String> messages = new ArrayList<>();

        type.validateValueForUpdate(classification, classification.getTypeName(), messages);

        if (!messages.isEmpty()) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, messages);
        }

        type.getNormalizedValueForUpdate(classification);
    }

    public static String getSoftRefFormattedValue(AtlasObjectId objectId) {
        return getSoftRefFormattedString(objectId.getTypeName(), objectId.getGuid());
    }

    private static String getSoftRefFormattedString(String typeName, String resolvedGuid) {
        return String.format(SOFT_REF_FORMAT, typeName, resolvedGuid);
    }

    public void importActivateEntity(AtlasVertex vertex, AtlasEntity entity) {
        AtlasGraphUtilsV2.setEncodedProperty(vertex, STATE_PROPERTY_KEY, ACTIVE);

        if (MapUtils.isNotEmpty(entity.getRelationshipAttributes())) {
            Set<String> relatedEntitiesGuids = getRelatedEntitiesGuids(entity);
            activateEntityRelationships(vertex, relatedEntitiesGuids);
        }
    }

    private void activateEntityRelationships(AtlasVertex vertex, Set<String> relatedEntitiesGuids) {
        Iterator<AtlasEdge> edgeIterator = vertex.getEdges(AtlasEdgeDirection.BOTH).iterator();

        while (edgeIterator.hasNext()) {
            AtlasEdge edge = edgeIterator.next();

            if (AtlasGraphUtilsV2.getState(edge) != DELETED) {
                continue;
            }

            final String relatedEntityGuid;
            if (Objects.equals(edge.getInVertex().getId(), vertex.getId())) {
                relatedEntityGuid = AtlasGraphUtilsV2.getIdFromVertex(edge.getOutVertex());
            } else {
                relatedEntityGuid = AtlasGraphUtilsV2.getIdFromVertex(edge.getInVertex());
            }

            if (StringUtils.isEmpty(relatedEntityGuid) || !relatedEntitiesGuids.contains(relatedEntityGuid)) {
                continue;
            }

            edge.setProperty(STATE_PROPERTY_KEY, AtlasRelationship.Status.ACTIVE);
        }
    }

    private Set<String> getRelatedEntitiesGuids(AtlasEntity entity) {
        Set<String> relGuidsSet = new HashSet<>();

        for (Object o : entity.getRelationshipAttributes().values()) {
            if (o instanceof AtlasObjectId) {
                relGuidsSet.add(((AtlasObjectId) o).getGuid());
            } else if (o instanceof List) {
                for (Object id : (List) o) {
                    if (id instanceof AtlasObjectId) {
                        relGuidsSet.add(((AtlasObjectId) id).getGuid());
                    }
                }
            }
        }
        return relGuidsSet;
    }

    public static void validateCustomAttributes(AtlasEntity entity) throws AtlasBaseException {
        Map<String, String> customAttributes = entity.getCustomAttributes();

        if (MapUtils.isNotEmpty(customAttributes)) {
            for (Map.Entry<String, String> entry : customAttributes.entrySet()) {
                String key   = entry.getKey();
                String value = entry.getValue();

                if (key.length() > CUSTOM_ATTRIBUTE_KEY_MAX_LENGTH) {
                    throw new AtlasBaseException(AtlasErrorCode.INVALID_CUSTOM_ATTRIBUTE_KEY_LENGTH, key);
                }

                Matcher matcher = CUSTOM_ATTRIBUTE_KEY_REGEX.matcher(key);

                if (!matcher.matches()) {
                    throw new AtlasBaseException(AtlasErrorCode.INVALID_CUSTOM_ATTRIBUTE_KEY_CHARACTERS, key);
                }

                if (value.length() > CUSTOM_ATTRIBUTE_VALUE_MAX_LENGTH) {
                    throw new AtlasBaseException(AtlasErrorCode.INVALID_CUSTOM_ATTRIBUTE_VALUE, value, String.valueOf(CUSTOM_ATTRIBUTE_VALUE_MAX_LENGTH));
                }
            }
        }
    }

    public static void validateLabels(Set<String> labels) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(labels)) {
            for (String label : labels) {
                if (label.length() > LABEL_MAX_LENGTH.getInt()) {
                    throw new AtlasBaseException(AtlasErrorCode.INVALID_LABEL_LENGTH, label, String.valueOf(LABEL_MAX_LENGTH.getInt()));
                }

                Matcher matcher = LABEL_REGEX.matcher(label);

                if (!matcher.matches()) {
                    throw new AtlasBaseException(AtlasErrorCode.INVALID_LABEL_CHARACTERS, label);
                }
            }
        }
    }

    private List<AtlasEntity> updateClassificationText(AtlasClassification classification, Collection<AtlasVertex> propagatedVertices) throws AtlasBaseException {
        List<AtlasEntity> propagatedEntities = new ArrayList<>();

        if (fullTextMapperV2 != null && CollectionUtils.isNotEmpty(propagatedVertices)) {
            for(AtlasVertex vertex : propagatedVertices) {
                AtlasEntity entity = instanceConverter.getAndCacheEntity(GraphHelper.getGuid(vertex), ENTITY_CHANGE_NOTIFY_IGNORE_RELATIONSHIP_ATTRIBUTES);

                if (isActive(entity)) {
                    vertex.setProperty(CLASSIFICATION_TEXT_KEY, fullTextMapperV2.getClassificationTextForEntity(entity));
                    propagatedEntities.add(entity);
                }
            }
        }

        return propagatedEntities;
    }
}
