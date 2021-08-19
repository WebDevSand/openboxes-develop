/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.inventory

import grails.orm.PagedResultList
import grails.validation.ValidationException
import org.codehaus.groovy.grails.web.json.JSONObject
import org.hibernate.ObjectNotFoundException
import org.pih.warehouse.api.AvailableItem
import org.pih.warehouse.api.DocumentGroupCode
import org.pih.warehouse.api.PackPageItem
import org.pih.warehouse.api.PickPageItem
import org.pih.warehouse.api.StockMovement
import org.pih.warehouse.api.StockMovementItem
import org.pih.warehouse.api.StockMovementType
import org.pih.warehouse.api.SubstitutionItem
import org.pih.warehouse.api.SuggestedItem
import org.pih.warehouse.auth.AuthService
import org.pih.warehouse.core.ActivityCode
import org.pih.warehouse.core.Comment
import org.pih.warehouse.core.Constants
import org.pih.warehouse.core.Document
import org.pih.warehouse.core.DocumentCode
import org.pih.warehouse.core.EventCode
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User
import org.pih.warehouse.order.Order
import org.pih.warehouse.order.OrderItem
import org.pih.warehouse.order.ShipOrderCommand
import org.pih.warehouse.order.ShipOrderItemCommand
import org.pih.warehouse.picklist.Picklist
import org.pih.warehouse.picklist.PicklistItem
import org.pih.warehouse.product.Product
import org.pih.warehouse.product.ProductAssociationTypeCode
import org.pih.warehouse.receiving.ReceiptItem
import org.pih.warehouse.requisition.ReplenishmentTypeCode
import org.pih.warehouse.requisition.Requisition
import org.pih.warehouse.requisition.RequisitionItem
import org.pih.warehouse.requisition.RequisitionItemSortByCode
import org.pih.warehouse.requisition.RequisitionItemStatus
import org.pih.warehouse.requisition.RequisitionItemType
import org.pih.warehouse.requisition.RequisitionSourceType
import org.pih.warehouse.requisition.RequisitionStatus
import org.pih.warehouse.shipping.Container
import org.pih.warehouse.shipping.ReferenceNumber
import org.pih.warehouse.shipping.ReferenceNumberType
import org.pih.warehouse.shipping.Shipment
import org.pih.warehouse.shipping.ShipmentItem
import org.pih.warehouse.shipping.ShipmentStatusCode
import org.pih.warehouse.shipping.ShipmentType
import org.pih.warehouse.shipping.ShipmentWorkflow

class StockMovementService {

    def productService
    def identifierService
    def requisitionService
    def shipmentService
    def inventoryService
    def productAvailabilityService
    def locationService
    def dataService
    def forecastingService

    boolean transactional = true

    def grailsApplication

    def createStockMovement(StockMovement stockMovement) {
        if (!stockMovement.validate()) {
            throw new ValidationException("Invalid stock movement", stockMovement.errors)
        }

        return createRequisitionBasedStockMovement(stockMovement)
    }

    void transitionStockMovement(StockMovement stockMovement, JSONObject jsonObject) {
        if (stockMovement.requisition) {
            transitionRequisitionBasedStockMovement(stockMovement, jsonObject)
        }
        else {
            transitionShipmentBasedStockMovement(stockMovement, jsonObject)
        }
    }

    void transitionShipmentBasedStockMovement(StockMovement stockMovement, JSONObject jsonObject) {
        RequisitionStatus status =
                jsonObject.containsKey("status") ? jsonObject.status as RequisitionStatus : null
        if (status == RequisitionStatus.ISSUED) {
            issueShipmentBasedStockMovement(stockMovement.id)
        }
        else {
            throw new UnsupportedOperationException("Updating inbound status not yet supported")
        }
    }

    void transitionRequisitionBasedStockMovement(StockMovement stockMovement, JSONObject jsonObject) {
        StockMovementStatusCode status =
                jsonObject.containsKey("status") ? jsonObject.status as StockMovementStatusCode : null

        Boolean statusOnly =
                jsonObject.containsKey("statusOnly") ? jsonObject.getBoolean("statusOnly") : false

        // Update status only
        if (status && statusOnly) {
            RequisitionStatus requisitionStatus = RequisitionStatus.fromStockMovementStatus(stockMovementStatus)
            updateRequisitionStatus(stockMovement.id, requisitionStatus)
        }
        // Determine whether we need to rollback change,
        else {

            // Determine whether we need to rollback changes
            Boolean rollback =
                    jsonObject.containsKey("rollback") ? jsonObject.getBoolean("rollback") : false

            if (rollback) {
                rollbackStockMovement(stockMovement.id)
            }

            if (status) {
                switch (status) {
                    //RequisitionStatus.CREATED:
                    case StockMovementStatusCode.CREATED:
                        break
                    //RequisitionStatus.EDITING:
                    case StockMovementStatusCode.REQUESTED:
                        break
                    //RequisitionStatus.VERIFYING:
                    case StockMovementStatusCode.VALIDATED:
                        break
                    // RequisitionStatus.PICKING:
                    case StockMovementStatusCode.PICKING:
                        // Clear picklist
                        Boolean shouldClearPicklist = jsonObject.containsKey("clearPicklist") ?
                                jsonObject.getBoolean("clearPicklist") : Boolean.FALSE

                        if (shouldClearPicklist) {
                            clearPicklist(stockMovement)
                        }

                        // Create picklist
                        Boolean shouldCreatePicklist = jsonObject.containsKey("createPicklist") ?
                                jsonObject.getBoolean("createPicklist") : Boolean.FALSE

                        if (shouldCreatePicklist) {
                            createPicklist(stockMovement)
                        }

                        break
                    case StockMovementStatusCode.PICKED:
                    case StockMovementStatusCode.PACKED:
                    case StockMovementStatusCode.CHECKING:
                    case StockMovementStatusCode.CHECKED:
                        createShipment(stockMovement)
                        break
                    case StockMovementStatusCode.DISPATCHED:
                        issueRequisitionBasedStockMovement(stockMovement.id)
                        break
                    default:
                        throw new IllegalArgumentException("Cannot update status with invalid status ${jsonObject.status}")
                        break

                }
                // If the dependent actions were updated properly then we can update the
                RequisitionStatus requisitionStatus = RequisitionStatus.fromStockMovementStatus(status)
                updateRequisitionStatus(stockMovement.id, requisitionStatus)
            }
        }
    }


    void updateRequisitionStatus(String id, RequisitionStatus status) {

        log.info "Update status ${id} " + status
        Requisition requisition = Requisition.get(id)
        if (status == RequisitionStatus.CHECKING) {
            Shipment shipment = requisition.shipment
            shipment?.expectedShippingDate = new Date()
        }

        if (!(status in RequisitionStatus.list())) {
            throw new IllegalStateException("Transition from ${requisition.status.name()} to ${status.name()} is not allowed")
        } else if (status < requisition.status) {
            // Ignore backwards state transitions since it occurs normally when users go back and edit pages earlier in the workflow
            log.warn("Transition from ${requisition.status.name()} to ${status.name()} is not allowed - use rollback instead")
        } else {
            requisition.status = status
            requisition.save(flush: true)
        }
    }


    StockMovement updateStockMovement(StockMovement stockMovement) {
        if (!stockMovement.validate()) {
            throw new ValidationException("Invalid stock movement", stockMovement.errors)
        }

        if (stockMovement.requisition) {
            return updateRequisitionBasedStockMovement(stockMovement)
        } else {
            return updateShipmentBasedStockMovement(stockMovement)
        }
    }


    StockMovement updateShipmentBasedStockMovement(StockMovement stockMovement) {
        log.info "Update stock movement " + new JSONObject(stockMovement.toJson()).toString(4)

        Shipment shipment = Shipment.get(stockMovement.id)
        if (!shipment) {
            throw new ObjectNotFoundException(stockMovement.id, StockMovement.class.toString())
        }
        if (stockMovement.destination) shipment.destination = stockMovement.destination
        if (stockMovement.origin) shipment.origin = stockMovement.origin
        if (stockMovement.description) shipment.description = stockMovement.description
        if (stockMovement.requestedBy) shipment.createdBy = stockMovement.requestedBy
        if (stockMovement.dateRequested) shipment.dateCreated = stockMovement.dateRequested
        shipment.name = stockMovement.generateName()

        if (stockMovement?.stocklist?.id) {
            throw new UnsupportedOperationException("Stocklists not yet supported for inbound stock movements")
        }
        if (shipment.hasErrors() || !shipment.save(flush: true)) {
            throw new ValidationException("Invalid shipment", shipment.errors)
        }

        return StockMovement.createFromShipment(shipment)
    }


    StockMovement updateRequisitionBasedStockMovement(StockMovement stockMovement) {
        Requisition requisition = Requisition.get(stockMovement.id)
        if (!requisition) {
            throw new ObjectNotFoundException(stockMovement.id, StockMovement.class.toString())
        }

        if (stockMovement.destination) requisition.destination = stockMovement.destination
        if (stockMovement.origin) requisition.origin = stockMovement.origin
        if (stockMovement.description) requisition.description = stockMovement.description
        if (stockMovement.requestedBy) requisition.requestedBy = stockMovement.requestedBy
        if (stockMovement.dateRequested) requisition.dateRequested = stockMovement.dateRequested
        if (stockMovement.requestType) requisition.type = stockMovement.requestType
        requisition.name = stockMovement.generateName()

        if (requisition.requisitionTemplate?.id != stockMovement.stocklist?.id) {
            removeRequisitionItems(requisition)
            addStockListItemsToRequisition(stockMovement, requisition)
            requisition.requisitionTemplate = stockMovement.stocklist
        }

        if (requisition.hasErrors() || !requisition.save(flush: true)) {
            throw new ValidationException("Invalid requisition", requisition.errors)
        }

        updateShipmentOnRequisitionChange(stockMovement)
        StockMovement savedStockMovement = StockMovement.createFromRequisition(requisition.refresh())

        createMissingPicklistItems(savedStockMovement)
        createMissingShipmentItems(savedStockMovement)

        return savedStockMovement
    }

    void updateRequisitionOnShipmentChange(StockMovement stockMovement) {
        log.info "Update stock movement " + new JSONObject(stockMovement.toJson()).toString(4)

        Requisition requisition = Requisition.get(stockMovement.id)
        if (!requisition) {
            throw new ObjectNotFoundException(stockMovement.id, StockMovement.class.toString())
        }

        requisition.name = stockMovement.description == requisition.description && requisition.destination == stockMovement.destination ? stockMovement.name : stockMovement.generateName()
        requisition.destination = stockMovement.destination
        requisition.description = stockMovement.description

        if (requisition.hasErrors() || !requisition.save(flush: true)) {
            throw new ValidationException("Invalid requisition", requisition.errors)
        }
    }

    void deleteStockMovement(String id) {
        StockMovement stockMovement = getStockMovement(id)
        deleteStockMovement(stockMovement)
    }

    void deleteStockMovement(StockMovement stockMovement) {
        if (stockMovement?.requisition) {
            def shipments = stockMovement?.requisition?.shipments
            shipments.toArray().each { Shipment shipment ->
                stockMovement?.requisition.removeFromShipments(shipment)
                if (!shipment?.events?.empty) {
                    shipmentService.rollbackLastEvent(shipment)
                }
                shipmentService.deleteShipment(shipment)
            }
            requisitionService.deleteRequisition(stockMovement?.requisition)
        }
        else {
            shipmentService.deleteShipment(stockMovement?.shipment)
        }
    }

    def getStockMovements(StockMovement criteria, Map params) {
        params.includeStockMovementItems = false
        switch(criteria.stockMovementType) {
            case StockMovementType.OUTBOUND:
                return getOutboundStockMovements(criteria, params)
            case StockMovementType.INBOUND:
                return getInboundStockMovements(criteria, params)
            default:
                throw new IllegalArgumentException("Origin and destination cannot be the same")
        }
    }


    def getInboundStockMovements(Integer maxResults, Integer offset) {
        return getInboundStockMovements(new StockMovement(), [:], maxResults, offset)
    }

    def getInboundStockMovements(StockMovement criteria, Map params) {
        def shipments = Shipment.createCriteria().list(max: params.max, offset: params.offset) {

            if (criteria?.identifier || criteria.name || criteria?.description) {
                or {
                    if (criteria?.identifier) {
                        ilike("shipmentNumber", criteria.identifier)
                    }
                    if (criteria?.name) {
                        ilike("name", criteria.name)
                    }
                    if (criteria?.description) {
                        ilike("description", criteria.description)
                    }
                }
            }
            if (criteria.destination) eq("destination", criteria.destination)
            if (criteria.origin) eq("origin", criteria.origin)
            if (criteria.receiptStatusCodes) 'in'("currentStatus", criteria.receiptStatusCodes)
            if (criteria.createdBy) {
                eq("createdBy", criteria?.createdBy)
            }
            if (criteria.requestedBy) {
                requisition {
                    eq("requestedBy", criteria?.requestedBy)
                }
            }
            if (criteria.updatedBy) {
                eq("updatedBy", criteria.updatedBy)
            }
            if(params.createdAfter) {
                ge("dateCreated", params.createdAfter)
            }
            if(params.createdBefore) {
                le("dateCreated", params.createdBefore)
            }

            order("dateCreated", "desc")
        }
        def stockMovements = shipments.collect { Shipment shipment ->
            if (shipment.requisition) {
                return StockMovement.createFromRequisition(shipment.requisition, params.includeStockMovementItems)
            }
            else {
                return StockMovement.createFromShipment(shipment, params.includeStockMovementItems)
            }
        }
        return new PagedResultList(stockMovements, shipments.totalCount)
    }

    def getOutboundStockMovements(Integer maxResults, Integer offset) {
        return getOutboundStockMovements(new StockMovement(), [maxResults:maxResults, offset:offset])
    }

    def getOutboundStockMovements(StockMovement stockMovement, Map params) {
        log.info "Stock movement: ${stockMovement?.shipmentStatusCode}"

        def requisitions = Requisition.createCriteria().list(max: params.max, offset: params.offset) {
            eq("isTemplate", Boolean.FALSE)

            if (stockMovement?.receiptStatusCodes) {
                shipments {
                    'in'("currentStatus", stockMovement.receiptStatusCodes)
                }
            }

            if (stockMovement?.identifier || stockMovement.name || stockMovement?.description) {
                or {
                    if (stockMovement?.identifier) {
                        ilike("requestNumber", stockMovement.identifier)
                    }
                    if (stockMovement?.name) {
                        ilike("name", stockMovement.name)
                    }
                    if (stockMovement?.description) {
                        ilike("description", stockMovement.description)
                    }
                }
            }

            if (stockMovement.destination == stockMovement?.origin) {
                or {
                    if (stockMovement?.destination) {
                        eq("destination", stockMovement.destination)
                    }
                    if (stockMovement?.origin) {
                        eq("origin", stockMovement.origin)
                    }
                }
            } else {
                if (stockMovement?.destination) {
                    eq("destination", stockMovement.destination)
                }
                if (stockMovement?.origin) {
                    eq("origin", stockMovement.origin)
                }
            }
            if (stockMovement.requisitionStatusCodes) {
                'in'("status", stockMovement.requisitionStatusCodes)
            }
            if (stockMovement.requestedBy) {
                eq("requestedBy", stockMovement.requestedBy)
            }
            if (stockMovement.createdBy) {
                eq("createdBy", stockMovement.createdBy)
            }
            if (stockMovement.updatedBy) {
                eq("updatedBy", stockMovement.updatedBy)
            }
            if (stockMovement.requestType) {
                eq("type", stockMovement.requestType)
            }
            if (stockMovement.sourceType) {
                eq ('sourceType', stockMovement.sourceType)
                if (stockMovement.sourceType == RequisitionSourceType.ELECTRONIC) {
                    not {
                        'in'("status", [RequisitionStatus.CREATED, RequisitionStatus.ISSUED, RequisitionStatus.CANCELED])
                    }
                }
            }
            if(params.createdAfter) {
                ge("dateCreated", params.createdAfter)
            }
            if(params.createdBefore) {
                le("dateCreated", params.createdBefore)
            }
            if (params.sort && params.order) {
                order(params.sort, params.order)
            } else {
                order("statusSortOrder", "asc")
                order("dateCreated", "desc")
            }
        }

        def stockMovements = requisitions.collect { requisition ->
            return StockMovement.createFromRequisition(requisition, params.includeStockMovementItems)
        }

        return new PagedResultList(stockMovements, requisitions.totalCount)
    }


    StockMovement getStockMovement(String id) {
        return getStockMovement(id, (String) null)
    }

    StockMovement getStockMovement(String id, String stepNumber) {
        Requisition requisition = Requisition.get(id)
        if (requisition) {
            return getRequisitionBasedStockMovement(requisition, stepNumber)
        } else {
            Shipment shipment = Shipment.get(id)
            if (shipment?.requisition) {
                log.info "Shipment.requisition ${shipment.requisition}"
                return getRequisitionBasedStockMovement(shipment.requisition, stepNumber)
            }
            else if (shipment) {
                log.info "Shipment ${shipment}"
                return getShipmentBasedStockMovement(shipment)
            }
            else {
                throw new ObjectNotFoundException(id, StockMovement.class.toString())
            }
        }
    }

    StockMovement getShipmentBasedStockMovement(Shipment shipment) {
        StockMovement stockMovement = StockMovement.createFromShipment(shipment)
        stockMovement.documents = getDocuments(stockMovement)
        return stockMovement
    }

    StockMovement getRequisitionBasedStockMovement(Requisition requisition, String stepNumber) {
        StockMovement stockMovement = StockMovement.createFromRequisition(requisition)
        stockMovement.documents = getDocuments(stockMovement)
        return stockMovement
    }

    StockMovementItem getStockMovementItem(String id) {
        RequisitionItem requisitionItem = RequisitionItem.get(id)
        return StockMovementItem.createFromRequisitionItem(requisitionItem)
    }

    void removeStockMovementItem(String id) {
        RequisitionItem requisitionItem = RequisitionItem.get(id)
        ShipmentItem shipmentItem = ShipmentItem.get(id)
        if (requisitionItem) {
            removeRequisitionItem(requisitionItem)
        } else {
            removeShipmentItem(shipmentItem)
        }
    }

    def getPendingRequisitionItems(Location origin) {
        def requisitionItems = RequisitionItem.createCriteria().list {
            and {
                gt("quantityApproved", 0)
                requisition {
                    and {
                        eq("origin", origin)
                        'in'("status", [RequisitionStatus.PICKED, RequisitionStatus.CHECKING])
                    }
                }
            }
        }
        return requisitionItems
    }

    def getStockMovementItems(String id, String stepNumber, String max, String offset) {
        // FIXME should get stock movement instead of requisition
        Requisition requisition = Requisition.get(id)
        List<StockMovementItem> stockMovementItems = []

        if (stepNumber == '3') {
            List editPageItems = getEditPageItems(requisition, max, offset)

            if (requisition && requisition.sourceType == RequisitionSourceType.ELECTRONIC) {
                def quantityOnHandRequestingMap = productAvailabilityService.getQuantityOnHand(editPageItems.collect { it.product }, requisition.destination)
                        .inject([:]) {map, item -> map << [(item.prod.id): item.quantityOnHand]}

                editPageItems = editPageItems.collect { editPageItem ->
                    // origin = fulfilling, destination = requesting
                    editPageItem << [quantityOnHandRequesting: quantityOnHandRequestingMap[editPageItem.product.id]]

                    def template = requisition.requisitionTemplate
                    if (!template || (template && template.replenishmentTypeCode == ReplenishmentTypeCode.PULL)) {
                        def quantityDemand = forecastingService.getDemand(requisition.destination, editPageItem.product)
                        editPageItem << [
                            quantityDemand                  : quantityDemand?.monthlyDemand?:0,
                            demandPerReplenishmentPeriod    : Math.ceil((quantityDemand?.dailyDemand?:0) * (template?.replenishmentPeriod?:30))
                        ]
                    } else {
                        def stocklist = Requisition.get(requisition.requisitionTemplate.id)
                        def quantityOnStocklist = 0
                        RequisitionItemSortByCode sortByCode = requisition.requisitionTemplate?.sortByCode ?: RequisitionItemSortByCode.SORT_INDEX
                        stocklist."${sortByCode.methodName}"?.eachWithIndex { item, index ->
                            if (item.product == editPageItem.product && (index * 100 == editPageItem.sortOrder || index == editPageItem.sortOrder)) {
                                quantityOnStocklist = item.quantity
                            }
                        }
                        editPageItem << [quantityOnStocklist: quantityOnStocklist]
                    }
                }
            }
            return editPageItems
        }

        if (requisition) {
            List <RequisitionItem> requisitionItems = []
            if (max != null && offset != null) {
                requisitionItems = RequisitionItem.createCriteria().list(max: max.toInteger(), offset: offset.toInteger()) {
                    eq("requisition", requisition)
                    isNull("parentRequisitionItem")
                    order("orderIndex", 'asc')
                }
            } else {
                requisitionItems = RequisitionItem.createCriteria().list() {
                    eq("requisition", requisition)
                    isNull("parentRequisitionItem")
                    order("orderIndex", 'asc')
                }
            }
            requisitionItems.each { requisitionItem ->
                StockMovementItem stockMovementItem = StockMovementItem.createFromRequisitionItem(requisitionItem)
                stockMovementItems.add(stockMovementItem)
            }
        } else {
            Shipment shipment = Shipment.get(id)
            List <ShipmentItem> shipmentItems = []
            if (max != null && offset != null) {
                shipmentItems = ShipmentItem.createCriteria().list(max: max.toInteger(), offset: offset.toInteger()) {
                    eq("shipment", shipment)
                    order("sortOrder", 'asc')
                }
            } else {
                shipmentItems = ShipmentItem.createCriteria().list() {
                    eq("shipment", shipment)
                    order("sortOrder", 'asc')
                }
            }
            shipmentItems.each { shipmentItem ->
                StockMovementItem stockMovementItem = StockMovementItem.createFromShipmentItem(shipmentItem)
                if (stockMovementItem.inventoryItem) {
                    def quantity = productAvailabilityService.getQuantityOnHand(stockMovementItem.inventoryItem)
                    stockMovementItem.inventoryItem.quantity = quantity
                }
                stockMovementItems.add(stockMovementItem)
            }
        }

        switch(stepNumber) {
            case "2":
                if (requisition && requisition.sourceType == RequisitionSourceType.ELECTRONIC) {
                    stockMovementItems = stockMovementItems.collect { stockMovementItem ->
                        def quantityOnHand = productAvailabilityService.getQuantityOnHand(stockMovementItem.product, requisition.destination)
                        def template = requisition.requisitionTemplate
                        if (!template || (template && template.replenishmentTypeCode == ReplenishmentTypeCode.PULL)) {
                            def demand = forecastingService.getDemand(requisition.destination, stockMovementItem.product)
                            [
                                    id                              : stockMovementItem.id,
                                    product                         : stockMovementItem.product,
                                    productCode                     : stockMovementItem.productCode,
                                    quantityOnHand                  : quantityOnHand ?: 0,
                                    quantityAllowed                 : stockMovementItem.quantityAllowed,
                                    comments                        : stockMovementItem.comments,
                                    quantityRequested               : stockMovementItem.quantityRequested,
                                    statusCode                      : stockMovementItem.statusCode,
                                    sortOrder                       : stockMovementItem.sortOrder,
                                    monthlyDemand                   : demand?.monthlyDemand?:0,
                                    demandPerReplenishmentPeriod    : Math.ceil((demand?.dailyDemand?:0) * (template?.replenishmentPeriod?:30))
                            ]
                        } else {
                            [
                                    id                  : stockMovementItem.id,
                                    product             : stockMovementItem.product,
                                    productCode         : stockMovementItem.productCode,
                                    quantityOnHand      : quantityOnHand ?: 0,
                                    quantityAllowed     : stockMovementItem.quantityAllowed,
                                    comments            : stockMovementItem.comments,
                                    quantityRequested   : stockMovementItem.quantityRequested,
                                    statusCode          : stockMovementItem.statusCode,
                                    sortOrder           : stockMovementItem.sortOrder,
                            ]
                        }
                    }
                }
                return stockMovementItems
            case "4":
                return getPickPageItems(id, max, offset)
            case "5":
                return getPackPageItems(id, max, offset)
            case "6":
                if (requisition && !requisition.origin.isSupplier() && requisition.origin.supports(ActivityCode.MANAGE_INVENTORY)) {
                    return getPackPageItems(id, max, offset)
                }
            default:
                return stockMovementItems
        }
    }

    def getEditPageItem(StockMovementItem stockMovementItem) {
        def query = """ select * FROM edit_page_item where id = :itemId """

        def data = dataService.executeQuery(query, ['itemId': stockMovementItem.id])

        List editPageItems = buildEditPageItems(data)

        return editPageItems ? editPageItems.first() : null
    }

    List getEditPageItems(Requisition requisition, String max, String offset) {
        def query = offset ?
                """ select * FROM edit_page_item where requisition_id = :requisition and requisition_item_type = 'ORIGINAL' ORDER BY sort_order limit :offset, :max; """ :
                """ select * FROM edit_page_item where requisition_id = :requisition and requisition_item_type = 'ORIGINAL' ORDER BY sort_order """

        def data = dataService.executeQuery(query, [
                'requisition': requisition.id,
                'offset'     : offset ? offset.toInteger() : null,
                'max'        : max ? max.toInteger() : null,
        ])

        return buildEditPageItems(data)
    }

    List buildEditPageItems(def data) {
        def editItemsIds = data.collect { "'$it.id'" }.join(',')

        def substitutionItemsMap = dataService.executeQuery("""
                    select
                       *
                    FROM substitution_item
                    where parent_requisition_item_id in (${editItemsIds})
                    """).groupBy { it.parent_requisition_item_id }

        def productsMap = Product.findAllByIdInList(data.collect { it.product_id })
                .inject([:]) {map, item -> map << [(item.id): item]}

        Requisition requisition = Requisition.get(data.first()?.requisition_id)
        def picklistItemsMap = requisition?.getPicklist()?.picklistItems?.groupBy { it.requisitionItem.id }

        def editPageItems = data.collect {
            def substitutionItems = substitutionItemsMap[it.id]

            def statusCode = substitutionItems ? RequisitionItemStatus.SUBSTITUTED :
                    it.quantity_revised != null ? RequisitionItemStatus.CHANGED : RequisitionItemStatus.APPROVED

            def picklistQtyForItem = (!picklistItemsMap || !picklistItemsMap[it.id]) ? 0 : picklistItemsMap[it.id].sum { it.quantity }

            [
                product                     : productsMap[it.product_id],
                productName                 : it.name,
                productCode                 : it.product_code,
                requisitionItemId           : it.id,
                requisition_id              : it.requisition_id,
                quantityRequested           : it.quantity,
                quantityRevised             : it.quantity_revised,
                quantityCanceled            : it.quantity_canceled,
                quantityConsumed            : it.quantity_demand,
                quantityOnHand              : it.quantity_on_hand,
                quantityAvailableToPromise  : (it.quantity_available_to_promise ?: 0) + picklistQtyForItem,
                substitutionStatus          : it.substitution_status,
                sortOrder                   : it.sort_order,
                reasonCode                  : it.cancel_reason_code,
                comments                    : it.comments,
                statusCode                  : statusCode.name(),
                substitutionItems           : substitutionItems.collect {
                    def picklistQtyForSubstitution = !picklistItemsMap[it.id] ? 0 : picklistItemsMap[it.id].sum { it.quantity }

                    [
                        product             : Product.get(it.product_id),
                        productId           : it.product_id,
                        productCode         : it.product_code,
                        productName         : it.name,
                        quantityAvailable   : (it.quantity_available_to_promise ?: 0) + picklistQtyForSubstitution,
                        quantityOnHand      : it.quantity_on_hand,
                        quantityConsumed    : it.quantity_demand,
                        quantitySelected    : it.quantity,
                        quantityRequested   : it.quantity
                    ]
                },
            ]
        }

        return editPageItems
    }

    List<PickPageItem> getPickPageItems(String id, String max, String offset) {
        List<PickPageItem> pickPageItems = []

        StockMovement stockMovement = getStockMovement(id)

        stockMovement.lineItems.each { stockMovementItem ->
            def items = getPickPageItems(stockMovementItem)
            pickPageItems.addAll(items)
        }

        if (max != null && offset != null) {
            return pickPageItems.subList(offset.toInteger(), offset.toInteger() + max.toInteger() > pickPageItems.size() ? pickPageItems.size() : offset.toInteger() + max.toInteger());
        }

        return pickPageItems
    }

    List<PackPageItem> getPackPageItems(String id, String max, String offset) {
        Set<PackPageItem> items = new LinkedHashSet<PackPageItem>()

        StockMovement stockMovement = getStockMovement(id)

        stockMovement.requisition?.picklist?.picklistItems?.sort { a, b ->
            a.sortOrder <=> b.sortOrder ?: a.id <=> b.id
        }?.each { PicklistItem picklistItem ->
            items.addAll(getPackPageItems(picklistItem))
        }

        List<PackPageItem> packPageItems = new ArrayList<PackPageItem>(items)

        if (max != null && offset != null) {
            return packPageItems.subList(offset.toInteger(), offset.toInteger() + max.toInteger() > packPageItems.size() ? packPageItems.size() : offset.toInteger() + max.toInteger())
        }

        return packPageItems
    }

    List<ReceiptItem> getStockMovementReceiptItems(StockMovement stockMovement) {
        return (stockMovement.requisition) ?
                getRequisitionBasedStockMovementReceiptItems(stockMovement) :
                getShipmentBasedStockMovementReceiptItems(stockMovement)
    }

    List<ReceiptItem> getRequisitionBasedStockMovementReceiptItems(StockMovement stockMovement) {
        def shipments = Shipment.findAllByRequisition(stockMovement.requisition)
        List<ReceiptItem> receiptItems = shipments*.receipts*.receiptItems?.flatten()?.sort { a, b ->
            a.shipmentItem?.requisitionItem?.orderIndex <=> b.shipmentItem?.requisitionItem?.orderIndex ?:
                    a.shipmentItem?.sortOrder <=> b.shipmentItem?.sortOrder ?:
                            a?.sortOrder <=> b?.sortOrder
        }
        return receiptItems
    }

    List<ReceiptItem> getShipmentBasedStockMovementReceiptItems(StockMovement stockMovement) {
        Shipment shipment = stockMovement.shipment
        List<ReceiptItem> receiptItems = shipment.receipts*.receiptItems?.flatten()?.sort { a, b ->
            a.shipmentItem?.requisitionItem?.orderIndex <=> b.shipmentItem?.requisitionItem?.orderIndex ?:
                    a.shipmentItem?.sortOrder <=> b.shipmentItem?.sortOrder ?:
                            a?.sortOrder <=> b?.sortOrder
        }
        return receiptItems
    }

    void clearPicklist(String id) {
        StockMovement stockMovement = getStockMovement(id)
        clearPicklist(stockMovement)
    }

    void clearPicklist(StockMovement stockMovement) {
        for (StockMovementItem stockMovementItem : stockMovement.lineItems) {
            clearPicklist(stockMovementItem)
        }
    }

    void clearPicklist(StockMovementItem stockMovementItem) {
        RequisitionItem requisitionItem = RequisitionItem.get(stockMovementItem.id)
        clearPicklist(requisitionItem)
    }

    void clearPicklist(RequisitionItem requisitionItem) {
        if (requisitionItem.modificationItem) {
            requisitionItem = requisitionItem.modificationItem
        }

        if (requisitionItem.pickReasonCode) {
            requisitionItem.pickReasonCode = null
        }

        Picklist picklist = requisitionItem?.requisition?.picklist
        log.info "Clear picklist"
        if (picklist) {
            picklist.picklistItems.findAll {
                it.requisitionItem == requisitionItem
            }.toArray().each {
                it.synchronousRequired = Boolean.TRUE
                picklist.removeFromPicklistItems(it)
                requisitionItem.removeFromPicklistItems(it)
                it.delete()
            }
            picklist.save()
        }
    }

    void createMissingPicklistItems(StockMovement stockMovement) {
        if (!stockMovement?.origin?.isSupplier() && stockMovement?.origin?.supports(ActivityCode.MANAGE_INVENTORY) && stockMovement.requisition?.status >= RequisitionStatus.PICKING) {
            stockMovement?.lineItems?.each { StockMovementItem stockMovementItem ->
                if (stockMovementItem.statusCode == 'SUBSTITUTED') {
                    for (StockMovementItem subStockMovementItem : stockMovementItem.substitutionItems) {
                        createMissingPicklistItems(subStockMovementItem)
                    }
                } else if (stockMovementItem.statusCode == 'CHANGED') {
                    if (!stockMovementItem.requisitionItem?.modificationItem?.picklistItems) {
                        createMissingPicklistItems(stockMovementItem)
                    }
                } else {
                    createMissingPicklistItems(stockMovementItem)
                }
            }
        }
    }

    void createMissingPicklistForStockMovementItem(StockMovementItem stockMovementItem) {
        if (stockMovementItem.statusCode == 'SUBSTITUTED') {
            for (StockMovementItem subStockMovementItem : stockMovementItem.substitutionItems) {
                createMissingPicklistItems(subStockMovementItem)
            }
        } else if (stockMovementItem.statusCode == 'CHANGED') {
            if (!stockMovementItem.requisitionItem?.modificationItem?.picklistItems) {
                createMissingPicklistItems(stockMovementItem)
            }
        } else {
            createMissingPicklistItems(stockMovementItem)
        }
    }

    void createMissingPicklistItems(StockMovementItem stockMovementItem) {
        if (!stockMovementItem.requisitionItem?.picklistItems) {
            createPicklist(stockMovementItem)
        }
    }

    /**
     * Create an automated picklist for the stock movenent associated with the given id.
     *
     * @param id
     */
    void createPicklist(String id) {
        StockMovement stockMovement = getStockMovement(id)
        createPicklist(stockMovement)
    }

    /**
     * Create an automated picklist for the given stock movement.
     *
     * @param stockMovement
     */
    void createPicklist(StockMovement stockMovement) {
        for (StockMovementItem stockMovementItem : stockMovement.lineItems) {
            if (stockMovementItem.statusCode == 'SUBSTITUTED') {
                for (StockMovementItem subStockMovementItem : stockMovementItem.substitutionItems) {
                    createPicklist(subStockMovementItem)
                }
            } else {
                createPicklist(stockMovementItem)
            }
        }
    }

    void createPicklist(StockMovementItem stockMovementItem) {
        log.info "Create picklist for stock movement item ${stockMovementItem.toJson()}"

        RequisitionItem requisitionItem = RequisitionItem.get(stockMovementItem.id)
        createPicklist(requisitionItem)
    }

    /**
     * Create an automated picklist for the given stock movement item.
     *
     * @param id
     */
    void createPicklist(RequisitionItem requisitionItem) {
        Location location = requisitionItem?.requisition?.origin
        Integer quantityRequired = requisitionItem?.calculateQuantityRequired()

        log.info "QUANTITY REQUIRED: ${quantityRequired}"

        if (quantityRequired) {
            // Retrieve all available items and then calculate suggested
            List<AvailableItem> availableItems = getAvailableItems(location, requisitionItem)
            log.info "Available items: ${availableItems}"
            List<SuggestedItem> suggestedItems = getSuggestedItems(availableItems, quantityRequired)
            log.info "Suggested items " + suggestedItems
            if (suggestedItems) {
                clearPicklist(requisitionItem)
                for (SuggestedItem suggestedItem : suggestedItems) {
                    createOrUpdatePicklistItem(requisitionItem,
                            null,
                            suggestedItem.inventoryItem,
                            suggestedItem.binLocation,
                            suggestedItem.quantityPicked.intValueExact(),
                            null,
                            null)
                }
            }
        }
    }

    void createOrUpdatePicklistItem(StockMovementItem stockMovementItem, PicklistItem picklistItem,
                                    InventoryItem inventoryItem, Location binLocation,
                                    Integer quantity, String reasonCode, String comment) {

        RequisitionItem requisitionItem = RequisitionItem.get(stockMovementItem.id)
        createOrUpdatePicklistItem(requisitionItem, picklistItem, inventoryItem, binLocation, quantity, reasonCode, comment)
    }

    void createOrUpdatePicklistItem(RequisitionItem requisitionItem, PicklistItem picklistItem,
                                    InventoryItem inventoryItem, Location binLocation,
                                    Integer quantity, String reasonCode, String comment) {

        Requisition requisition = requisitionItem.requisition

        Picklist picklist = Picklist.findByRequisition(requisition)
        if (!picklist) {
            picklist = new Picklist()
            picklist.requisition = requisition
        }

        // If one does not exist create it and add it to the list
        if (!picklistItem) {
            picklistItem = new PicklistItem()
            picklist.addToPicklistItems(picklistItem)
        }

        // Set pick reason code if it is different than the one that has already been added to the item
        if (reasonCode && requisitionItem.pickReasonCode != reasonCode) {
            requisitionItem.pickReasonCode = reasonCode
        }

        // Remove from picklist
        if (quantity == null) {
            picklist.removeFromPicklistItems(picklistItem)
        }
        // Populate picklist item
        else {

            // If we've modified the requisition item we need to associate picks with the modified item
            if (requisitionItem.modificationItem) {
                requisitionItem = requisitionItem.modificationItem
            }
            requisitionItem.addToPicklistItems(picklistItem)
            picklistItem.inventoryItem = inventoryItem
            picklistItem.binLocation = binLocation
            picklistItem.quantity = quantity
            picklistItem.reasonCode = reasonCode
            picklistItem.comment = comment
            picklistItem.sortOrder = requisitionItem.orderIndex
            picklistItem.synchronousRequired = Boolean.TRUE
        }
        picklist.save(flush: true)
    }

    void createOrUpdatePicklistItem(StockMovement stockMovement, List<PickPageItem> pickPageItems) {

        Requisition requisition = stockMovement.requisition
        Picklist picklist = requisition?.picklist
        if (!picklist) {
            picklist = new Picklist()
            picklist.requisition = requisition
        }
        pickPageItems.each { pickPageItem ->
            pickPageItem.picklistItems?.toArray()?.each { PicklistItem picklistItem ->
                // If one does not exist add it to the list
                if (!picklistItem.id) {
                    picklist.addToPicklistItems(picklistItem)
                }

                // Remove from picklist
                if (picklistItem.quantity <= 0) {
                    picklist.removeFromPicklistItems(picklistItem)
                    picklistItem.requisitionItem?.removeFromPicklistItems(picklistItem)
                }
            }
        }

        // FIXME Check to see if both of these are needed
        requisition.save()
        picklist.save()
    }

    Set<PicklistItem> getPicklistItems(RequisitionItem requisitionItem) {
        if (requisitionItem.modificationItem) {
            requisitionItem = requisitionItem.modificationItem
        }

        Picklist picklist = requisitionItem?.requisition?.picklist

        if (picklist) {
            return picklist.picklistItems.findAll {
                it.requisitionItem == requisitionItem
            }
        }

        return []
    }

    List<AvailableItem> getAvailableItems(Location location, RequisitionItem requisitionItem) {
        List<AvailableItem> availableItems = productAvailabilityService.getAllAvailableBinLocations(location, requisitionItem.product)
        def picklistItems = getPicklistItems(requisitionItem)

        return calculateQuantityAvailableToPromise(availableItems, picklistItems)
    }

    List<AvailableItem> calculateQuantityAvailableToPromise(List<AvailableItem> availableItems, def picklistItems) {
        for (PicklistItem picklistItem : picklistItems) {
            AvailableItem availableItem = availableItems.find {
                it.inventoryItem == picklistItem.inventoryItem && it.binLocation == picklistItem.binLocation
            }

            if (!availableItem) {
                availableItem = new AvailableItem(
                        inventoryItem: picklistItem.inventoryItem,
                        binLocation: picklistItem.binLocation,
                        quantityAvailable: 0,
                        quantityOnHand: picklistItem.quantity
                )

                availableItems.add(availableItem)
            }

            availableItem.quantityAvailable += picklistItem.quantity
        }

        return productAvailabilityService.sortAvailableItems(availableItems)
    }

    /**
     * Get a list of suggested items for the given stock movement item.
     *
     * @param stockMovementItem
     * @return
     */
    List getSuggestedItems(List<AvailableItem> availableItems, Integer quantityRequested) {

        List suggestedItems = []
        List<AvailableItem> autoPickableItems = availableItems?.findAll { it.quantityAvailable > 0 && it.autoPickable }

        // As long as quantity requested is less than the total available we can iterate through available items
        // and pick until quantity requested is 0. Otherwise, we don't suggest anything because the user must
        // choose anyway. This might be improved in the future.
        Integer quantityAvailable = autoPickableItems ? autoPickableItems?.sum {
            it.quantityAvailable
        } : 0
        if (quantityRequested <= quantityAvailable) {

            for (AvailableItem availableItem : autoPickableItems) {
                if (quantityRequested == 0)
                    break

                // The quantity to pick is either the quantity available (if less than requested) or
                // the quantity requested (if less than available).
                int quantityPicked = (quantityRequested >= availableItem.quantityAvailable) ?
                        availableItem.quantityAvailable : quantityRequested

                log.info "Suggested quantity ${quantityPicked}"
                suggestedItems << new SuggestedItem(inventoryItem: availableItem?.inventoryItem,
                        binLocation: availableItem?.binLocation,
                        quantityAvailable: availableItem?.quantityAvailable,
                        quantityRequested: quantityRequested,
                        quantityPicked: quantityPicked)
                quantityRequested -= quantityPicked
            }
        }
        return suggestedItems
    }

    List<SubstitutionItem> getAvailableSubstitutions(Location location, RequisitionItem requisitionItem) {
        Product product = requisitionItem.product
        List<SubstitutionItem> availableSubstitutions

        if (location) {
            def productAssociations =
                    productService.getProductAssociations(product, [ProductAssociationTypeCode.SUBSTITUTE])

            availableSubstitutions = productAssociations.collect { productAssociation ->

                def associatedProduct = productAssociation.associatedProduct
                def availableItems

                if (requisitionItem.substitutionItems) {
                    def picklistItems = requisitionItem.substitutionItems.findAll { it.product == associatedProduct }
                            .collect { it.picklistItems }?.flatten()

                    availableItems = productAvailabilityService.getAllAvailableBinLocations(location, associatedProduct)
                    availableItems = calculateQuantityAvailableToPromise(availableItems, picklistItems)
                } else {
                    availableItems = productAvailabilityService.getAvailableBinLocations(location, associatedProduct)
                }

                log.info "Available items for substitution ${associatedProduct}: ${availableItems}"
                SubstitutionItem substitutionItem = new SubstitutionItem()
                substitutionItem.product = associatedProduct
                substitutionItem.productId = associatedProduct.id
                substitutionItem.productName = associatedProduct.name
                substitutionItem.productCode = associatedProduct.productCode
                substitutionItem.availableItems = availableItems
                return substitutionItem
            }
        }

        return availableSubstitutions.findAll { availableItems -> availableItems.quantityAvailable > 0 }
    }

    /**
     * Get a list of pick page items for the given stock movement item.
     *
     * @param stockMovementItem
     * @return
     */
    List getPickPageItems(StockMovementItem stockMovementItem) {
        List pickPageItems = []
        RequisitionItem requisitionItem = RequisitionItem.load(stockMovementItem.id)
        if (requisitionItem.isSubstituted()) {
            pickPageItems = requisitionItem.substitutionItems.sort { it.orderIndex }. collect {
                return buildPickPageItem(it, stockMovementItem.sortOrder)
            }
        } else if (requisitionItem.modificationItem) {
            pickPageItems << buildPickPageItem(requisitionItem.modificationItem, stockMovementItem.sortOrder)
        } else {
            if (!requisitionItem.isCanceled()) {
                pickPageItems << buildPickPageItem(requisitionItem, stockMovementItem.sortOrder)
            }
        }
        return pickPageItems
    }

    /**
     *
     * @param requisitionItem
     * @return
     */
    PickPageItem buildPickPageItem(RequisitionItem requisitionItem, Integer sortOrder) {

        if (!requisitionItem.picklistItems || (requisitionItem.picklistItems && requisitionItem.totalQuantityPicked() != requisitionItem.quantity &&
                !requisitionItem.picklistItems.reasonCode)) {
            createPicklist(requisitionItem)
        }
        PickPageItem pickPageItem = new PickPageItem(requisitionItem: requisitionItem,
                picklistItems: requisitionItem.picklistItems)
        Location location = requisitionItem?.requisition?.origin

        List<AvailableItem> availableItems = getAvailableItems(location, requisitionItem)
        Integer quantityRequired = requisitionItem?.calculateQuantityRequired()
        List<SuggestedItem> suggestedItems = getSuggestedItems(availableItems, quantityRequired)
        pickPageItem.availableItems = availableItems
        pickPageItem.suggestedItems = suggestedItems
        pickPageItem.sortOrder = requisitionItem.orderIndex ?: sortOrder

        return pickPageItem
    }


    List getPackPageItems(PicklistItem picklistItem) {
        List packPageItems = []
        List<ShipmentItem> shipmentItems = ShipmentItem.findAllByRequisitionItem(picklistItem?.requisitionItem)
        if (shipmentItems) {
            shipmentItems.sort { a, b ->
                a.sortOrder <=> b.sortOrder ?: b.id <=> a.id
            }.each { shipmentItem ->
                packPageItems << buildPackPageItem(shipmentItem)
            }
        }

        return packPageItems
    }

    PackPageItem buildPackPageItem(ShipmentItem shipmentItem) {
        String palletName = ""
        String boxName = ""
        if (shipmentItem?.container?.parentContainer) {
            palletName = shipmentItem?.container?.parentContainer?.name
            boxName = shipmentItem?.container?.name
        } else if (shipmentItem.container) {
            palletName = shipmentItem?.container?.name
        }

        return new PackPageItem(shipmentItem: shipmentItem, palletName: palletName, boxName: boxName)
    }

    StockMovement createShipmentBasedStockMovement(StockMovement stockMovement) {
        Shipment shipment = createInboundShipment(stockMovement)
        return StockMovement.createFromShipment(shipment)
    }

    Shipment createInboundShipment(ShipOrderCommand command) {
        Order order = command.order
        Shipment shipment = new Shipment()
        shipment.shipmentNumber = identifierService.generateShipmentIdentifier()
        shipment.expectedShippingDate = new Date()
        shipment.name = order.name ?: order.orderNumber
        shipment.description = order.orderNumber
        shipment.origin = order.origin
        shipment.destination = order.destination
        shipment.createdBy = order.orderedBy
        shipment.shipmentType = ShipmentType.get(Constants.DEFAULT_SHIPMENT_TYPE_ID)

        command.shipOrderItems.each { ShipOrderItemCommand orderItemCommand ->
            if (orderItemCommand.quantityToShip > 0) {
                OrderItem orderItem = orderItemCommand.orderItem
                ShipmentItem shipmentItem = new ShipmentItem()
                shipmentItem.lotNumber = orderItemCommand?.inventoryItem?.lotNumber
                shipmentItem.expirationDate = orderItemCommand?.inventoryItem?.expirationDate
                shipmentItem.product = orderItemCommand.orderItem.product
                shipmentItem.inventoryItem = orderItemCommand.inventoryItem
                shipmentItem.quantity = orderItemCommand.quantityToShip * orderItemCommand.orderItem.quantityPerUom
                shipmentItem.recipient = orderItemCommand.orderItem.recipient ?: order.orderedBy
                shipment.addToShipmentItems(shipmentItem)
                orderItem.addToShipmentItems(shipmentItem)
            }
        }

        if (!shipment?.shipmentItems || shipment.shipmentItems.size() == 0) {
            shipment.errors.rejectValue("shipmentItems", "shipment.mustContainAtLeastOneShipmentItem.message", "Shipment must contain at least one shipment item.")
        }

        if (shipment.hasErrors() || !shipment.save(flush: true)) {
            throw new ValidationException("Invalid shipment", shipment.errors)
        }
        if (order.hasErrors() || !order.save(flush: true)) {
            throw new ValidationException("Invalid order", order.errors)
        }

        return shipment
    }

    Shipment createInboundShipment(StockMovement stockMovement) {

        Shipment shipment = new Shipment()
        shipment.shipmentNumber = identifierService.generateShipmentIdentifier()
        shipment.expectedShippingDate = new Date()
        shipment.name = stockMovement.generateName()
        shipment.description = stockMovement.description
        shipment.origin = stockMovement.origin
        shipment.destination = stockMovement.destination
        shipment.shipmentType = ShipmentType.get(Constants.DEFAULT_SHIPMENT_TYPE_ID)

        stockMovement.lineItems.each { StockMovementItem stockMovementItem ->

            if (!stockMovementItem.inventoryItem) {
                stockMovementItem.inventoryItem =
                        inventoryService.findOrCreateInventoryItem(
                                stockMovementItem.product,
                                stockMovementItem?.lotNumber,
                                stockMovementItem?.expirationDate)
            }

            ShipmentItem shipmentItem = new ShipmentItem()
            shipmentItem.lotNumber = stockMovementItem.lotNumber
            shipmentItem.expirationDate = stockMovementItem.expirationDate
            shipmentItem.product = stockMovementItem.product
            shipmentItem.inventoryItem = stockMovementItem.inventoryItem
            shipmentItem.quantity = stockMovementItem.quantityRequested
            shipmentItem.sortOrder = stockMovementItem.sortOrder
            shipmentItem.recipient = stockMovementItem.recipient
            if (stockMovementItem.orderItemId) {
                OrderItem orderItem = OrderItem.get(stockMovementItem.orderItemId)
                shipmentItem.addToOrderItems(orderItem)
                shipment.save()
            }
            shipment.addToShipmentItems(shipmentItem)
        }

        if (shipment.hasErrors() || !shipment.save(flush: true)) {
            throw new ValidationException("Invalid shipment", shipment.errors)
        }

        return shipment
    }

    StockMovement createRequisitionBasedStockMovement(StockMovement stockMovement) {
        Requisition requisition = Requisition.get(stockMovement.id)
        if (!requisition) {
            requisition = new Requisition()
        }

        if (!requisition.status) {
            requisition.status = RequisitionStatus.CREATED
        }

        // Generate identifier if one has not been provided
        if (!stockMovement.identifier && !requisition.requestNumber) {
            requisition.requestNumber = identifierService.generateRequisitionIdentifier()
        }
        requisition.type = stockMovement.requestType
        requisition.sourceType = stockMovement.sourceType
        requisition.requisitionTemplate = stockMovement.stocklist
        requisition.description = stockMovement.description
        requisition.destination = stockMovement.destination
        requisition.origin = stockMovement.origin
        requisition.requestedBy = stockMovement.requestedBy
        requisition.dateRequested = stockMovement.dateRequested
        requisition.name = stockMovement.generateName()
        requisition.requisitionItems = []

        stockMovement.lineItems.each { stockMovementItem ->
            RequisitionItem requisitionItem = RequisitionItem.createFromStockMovementItem(stockMovementItem)
            requisition.addToRequisitionItems(requisitionItem)
        }

        addStockListItemsToRequisition(stockMovement, requisition)
        if (requisition.hasErrors() || !requisition.save(flush: true)) {
            throw new ValidationException("Invalid requisition", requisition.errors)
        }
        StockMovement savedStockMovement = StockMovement.createFromRequisition(requisition)

        createShipment(savedStockMovement)

        return savedStockMovement
    }

    void addStockListItemsToRequisition(StockMovement stockMovement, Requisition requisition) {
        // If the user specified a stocklist then we should automatically clone it as long as there are no
        // requisition items already added to the requisition
        RequisitionItemSortByCode sortByCode = stockMovement.stocklist?.sortByCode ?: RequisitionItemSortByCode.SORT_INDEX
        Integer orderIndex = 0
        if (stockMovement.stocklist && !requisition.requisitionItems) {
            stockMovement.stocklist."${sortByCode.methodName}".each { stocklistItem ->
                RequisitionItem requisitionItem = new RequisitionItem()
                requisitionItem.product = stocklistItem.product
                if (requisition.sourceType == RequisitionSourceType.ELECTRONIC) {
                    def quantityOnHand = productAvailabilityService.getQuantityOnHand(stocklistItem.product, requisition.destination)
                    def quantityRequested = quantityOnHand ? (stocklistItem.quantity - quantityOnHand > 0 ? stocklistItem.quantity - quantityOnHand : 0) : stocklistItem.quantity
                    requisitionItem.quantity = quantityRequested
                    requisitionItem.quantityApproved = quantityRequested
                } else {
                    requisitionItem.quantity = stocklistItem.quantity
                    requisitionItem.quantityApproved = stocklistItem.quantity
                }
                requisitionItem.orderIndex = orderIndex
                orderIndex += 100
                requisition.addToRequisitionItems(requisitionItem)
            }
        }
    }

    StockMovement updateItems(StockMovement stockMovement) {
        if (stockMovement.requisition) {
            return updateRequisitionBasedStockMovementItems(stockMovement)
        }
        else {
            return updateShipmentBasedStockMovementItems(stockMovement)
        }
    }

    void updateInventoryItems(StockMovement stockMovement) {
        if (stockMovement.lineItems) {
            stockMovement.lineItems.each { StockMovementItem stockMovementItem ->
                inventoryService.findAndUpdateOrCreateInventoryItem(stockMovementItem.product,
                        stockMovementItem.lotNumber, stockMovementItem.expirationDate)
            }
        }
    }

    StockMovement updateShipmentBasedStockMovementItems(StockMovement stockMovement) {
        log.info "update shipment items " + (new JSONObject(stockMovement.toJson())).toString(4)
        Shipment shipment = Shipment.get(stockMovement.id)

        if (stockMovement.lineItems) {

            // Perform lookup of inventory item before we start dealing with persistence
            stockMovement.lineItems.each { StockMovementItem stockMovementItem ->
                if (!stockMovementItem.inventoryItem) {
                    stockMovementItem.inventoryItem =
                            inventoryService.findOrCreateInventoryItem(
                                    stockMovementItem?.product,
                                    stockMovementItem?.lotNumber,
                                    stockMovementItem?.expirationDate)

                    // There's a case where the user might change the expiration date
                    if (stockMovementItem.inventoryItem.expirationDate != stockMovementItem.expirationDate) {
                        stockMovementItem.inventoryItem.expirationDate = stockMovementItem.expirationDate
                    }
                }
            }

            stockMovement.lineItems.each { StockMovementItem stockMovementItem ->
                ShipmentItem shipmentItem = findOrCreateShipmentItem(shipment, stockMovementItem.id)
                if (!stockMovementItem.quantityRequested) {
                    shipment.removeFromShipmentItems(shipmentItem)
                    shipmentItem.delete(flush: true)
                } else {
                    shipmentItem.lotNumber = stockMovementItem.lotNumber
                    shipmentItem.expirationDate = stockMovementItem.expirationDate
                    shipmentItem.product = stockMovementItem.product
                    shipmentItem.inventoryItem = stockMovementItem.inventoryItem
                    shipmentItem.quantity = stockMovementItem.quantityRequested
                    shipmentItem.recipient = stockMovementItem.recipient
                    shipmentItem.sortOrder = stockMovementItem.sortOrder
                    shipmentItem.container = createOrUpdateContainer(shipment, stockMovementItem.palletName, stockMovementItem.boxName)

                    if (stockMovementItem.orderItemId) {
                        OrderItem orderItem = OrderItem.get(stockMovementItem.orderItemId)
                        shipmentItem.addToOrderItems(orderItem)
                    }
                }
                shipmentItem.save()
            }
        }

        if (shipment.hasErrors() || !shipment.save()) {
            throw new ValidationException("Invalid shipment", shipment.errors)
        }

        return StockMovement.createFromShipment(Shipment.get(shipment.id))
    }


    ShipmentItem findOrCreateShipmentItem(Shipment shipment, String id) {
        log.info "find or create shipment item: " + id
        ShipmentItem shipmentItem
        if (id) {
            shipmentItem = shipment.shipmentItems.find { ShipmentItem si -> si.id == id }
            log.info "Found ${shipmentItem}"
            if (!shipmentItem) {
                throw new IllegalArgumentException("Could not find shipmente item with id ${id}")
            }
        }
        if (!shipmentItem) {
            log.info "Not found, create new and adding to shipment ${shipment.id}"
            shipmentItem = new ShipmentItem()
            shipment.addToShipmentItems(shipmentItem)
        }
        return shipmentItem
    }


    StockMovement updateRequisitionBasedStockMovementItems(StockMovement stockMovement) {
        Requisition requisition = Requisition.get(stockMovement.id)

        if (stockMovement.lineItems) {
            stockMovement.lineItems.each { StockMovementItem stockMovementItem ->
                RequisitionItem requisitionItem
                // Try to find a matching stock movement item
                if (stockMovementItem.id) {
                    requisitionItem = requisition.requisitionItems.find {
                        it.id == stockMovementItem.id
                    }
                    if (!requisitionItem) {
                        throw new IllegalArgumentException("Could not find stock movement item with ID ${stockMovementItem.id}")
                    }
                }

                // If requisition item is found, we update it
                if (requisitionItem) {
                    log.info "Item updated " + requisitionItem.id

                    removeShipmentAndPicklistItemsForModifiedRequisitionItem(requisitionItem)

                    if (!stockMovementItem.quantityRequested) {
                        log.info "Item deleted " + requisitionItem.id
                        requisitionItem.undoChanges()
                        requisition.removeFromRequisitionItems(requisitionItem)
                        requisitionItem.delete(flush: true)
                    } else {
                        if (stockMovementItem.quantityRequested != requisitionItem.quantity) {
                            requisitionItem.undoChanges()
                        }

                        requisitionItem.quantity = stockMovementItem.quantityRequested
                        requisitionItem.quantityApproved = stockMovementItem.quantityRequested

                        if (stockMovementItem.product) requisitionItem.product = stockMovementItem.product
                        if (stockMovementItem.inventoryItem) requisitionItem.inventoryItem = stockMovementItem.inventoryItem
                        if (stockMovementItem.sortOrder) requisitionItem.orderIndex = stockMovementItem.sortOrder

                        requisitionItem.recipient = stockMovementItem.recipient
                        requisitionItem.palletName = stockMovementItem.palletName
                        requisitionItem.boxName = stockMovementItem.boxName
                        requisitionItem.lotNumber = stockMovementItem.lotNumber
                        requisitionItem.expirationDate = stockMovementItem.expirationDate
                        requisitionItem.comment = stockMovementItem.comments
                    }
                }
                // Otherwise we create a new one
                else {
                    log.info "Item not found"
                    if (stockMovementItem.quantityRevised) {
                        throw new IllegalArgumentException("Cannot specify quantityRevised when creating a new item")
                    }
                    requisitionItem = new RequisitionItem()
                    requisitionItem.product = stockMovementItem.product
                    requisitionItem.inventoryItem = stockMovementItem.inventoryItem
                    requisitionItem.quantity = stockMovementItem.quantityRequested
                    requisitionItem.quantityApproved = stockMovementItem.quantityRequested
                    requisitionItem.recipient = stockMovementItem.recipient
                    requisitionItem.palletName = stockMovementItem.palletName
                    requisitionItem.boxName = stockMovementItem.boxName
                    requisitionItem.lotNumber = stockMovementItem.lotNumber
                    requisitionItem.expirationDate = stockMovementItem.expirationDate
                    requisitionItem.orderIndex = stockMovementItem.sortOrder
                    requisitionItem.comment = stockMovementItem.comments
                    requisition.addToRequisitionItems(requisitionItem)
                }
            }
        }

        if (requisition.hasErrors() || !requisition.save(flush: true)) {
            throw new ValidationException("Invalid requisition", requisition.errors)
        }

        def updatedStockMovement = StockMovement.createFromRequisition(requisition)

        if (updatedStockMovement.lineItems) {
            updatedStockMovement.lineItems.each { StockMovementItem stockMovementItem ->
                InventoryItem inventoryItem = inventoryService.findOrCreateInventoryItem(stockMovementItem.product,
                        stockMovementItem.lotNumber, stockMovementItem.expirationDate)
                def quantity = productAvailabilityService.getQuantityOnHand(inventoryItem)
                inventoryItem.quantity = quantity
                stockMovementItem.inventoryItem = inventoryItem
            }
        }

        createMissingPicklistItems(updatedStockMovement)
        createMissingShipmentItems(updatedStockMovement)

        return updatedStockMovement
    }

    List reviseItems(StockMovement stockMovement) {
        Requisition requisition = Requisition.get(stockMovement.id)
        def revisedItems = []

        if (stockMovement.lineItems) {
            stockMovement.lineItems.each { StockMovementItem stockMovementItem ->
                RequisitionItem requisitionItem = requisition.requisitionItems.find {
                    it.id == stockMovementItem.id
                }

                if (!requisitionItem) {
                    throw new IllegalArgumentException("Could not find stock movement item with ID ${stockMovementItem.id}")
                }

                removeShipmentAndPicklistItemsForModifiedRequisitionItem(requisitionItem)

                log.info "Item revised " + requisitionItem.id

                // Cannot cancel quantity if it has already been canceled
                if (requisitionItem.quantityCanceled) {
                    requisitionItem.undoChanges()
                }

                requisitionItem.changeQuantity(
                        stockMovementItem?.quantityRevised?.intValueExact(),
                        stockMovementItem.reasonCode,
                        stockMovementItem.comments)

                requisitionItem.quantityApproved = 0
                stockMovementItem.statusCode = "CHANGED"
            }
        }

        if (requisition.hasErrors() || !requisition.save(flush: true)) {
            throw new ValidationException("Invalid requisition", requisition.errors)
        }

        createMissingPicklistItems(stockMovement)
        createMissingShipmentItems(stockMovement)

        stockMovement.lineItems.each { StockMovementItem stockMovementItem ->
            if (stockMovementItem.statusCode == 'CHANGED') {
                def editPageItem = getEditPageItem(stockMovementItem)
                revisedItems.add(editPageItem)
            }
        }

        return revisedItems
    }

    void substituteItem(StockMovementItem stockMovementItem) {
        revertItem(stockMovementItem)

        log.info "Substitute stock movement item ${stockMovementItem}"

        RequisitionItem requisitionItem = stockMovementItem.requisitionItem
        Requisition requisition = requisitionItem.requisition

        if (stockMovementItem.substitutionItems) {
            stockMovementItem.substitutionItems?.each { subItem ->
                requisitionItem.chooseSubstitute(
                        subItem.newProduct,
                        null,
                        subItem?.newQuantity?.intValueExact(),
                        subItem.reasonCode,
                        subItem.comments,
                        subItem.sortOrder)
                requisitionItem.quantityApproved = 0
            }
        }

        requisitionItem.save()

        createMissingPicklistForStockMovementItem(StockMovementItem.createFromRequisitionItem(requisitionItem))
        createMissingShipmentItem(requisitionItem)
    }

    def revertItem(StockMovementItem stockMovementItem) {
        removeShipmentAndPicklistItemsForModifiedRequisitionItem(stockMovementItem)

        log.info "Revert the stock movement item ${stockMovementItem}"

        RequisitionItem requisitionItem = stockMovementItem.requisitionItem
        requisitionItem.undoChanges()
        requisitionItem.quantityApproved = requisitionItem.quantity
        requisitionItem.save(flush: true)

        createMissingPicklistForStockMovementItem(StockMovementItem.createFromRequisitionItem(requisitionItem))
        createMissingShipmentItem(requisitionItem)
    }

    /**
     * Remove all requisition items for a requisition, modification and substitution items first.
     *
     * @param requisition
     */
    void removeRequisitionItems(Requisition requisition) {

        def originalRequisitionItems =
                requisition.requisitionItems.findAll { RequisitionItem requisitionItem ->
                    requisitionItem.requisitionItemType == RequisitionItemType.ORIGINAL
                }
        def otherRequisitionItems =
                requisition.requisitionItems.minus(originalRequisitionItems)

        // Remove substitutions and modifications, then remove the original requisition items
        removeRequisitionItems(otherRequisitionItems)
        removeRequisitionItems(originalRequisitionItems)
    }

    void removeRequisitionItems(Set<RequisitionItem> requisitionItems) {
        requisitionItems?.toArray()?.each { RequisitionItem requisitionItem ->
            removeRequisitionItem(requisitionItem)
        }
    }

    void removeRequisitionItem(RequisitionItem requisitionItem) {
        Requisition requisition = requisitionItem.requisition
        removeShipmentAndPicklistItemsForModifiedRequisitionItem(requisitionItem)
        requisitionItem.undoChanges()
        requisitionItem.save(flush: true)

        requisition.removeFromRequisitionItems(requisitionItem)
        requisitionItem.delete()
        requisition.save(flush: true)
    }

    void removeShipmentItem(ShipmentItem shipmentItem) {
        Shipment shipment = shipmentItem.shipment
        OrderItem orderItem = OrderItem.get(shipmentItem.orderItemId)
        if (orderItem) {
            orderItem.removeFromShipmentItems(shipmentItem)
        }
        shipment.removeFromShipmentItems(shipmentItem)
        shipmentItem.delete()
    }

    void removeShipmentItems(Set<ShipmentItem> shipmentItems) {
        shipmentItems?.toArray()?.each {shipmentItem ->
            removeShipmentItem(shipmentItem)
        }
    }

    void removeShipmentItemsForModifiedRequisitionItem(StockMovementItem stockMovementItem) {
        RequisitionItem requisitionItem = RequisitionItem.get(stockMovementItem?.id)
        removeShipmentItemsForModifiedRequisitionItem(requisitionItem)
    }

    void removeShipmentAndPicklistItemsForModifiedRequisitionItem(StockMovementItem stockMovementItem) {
        RequisitionItem requisitionItem = RequisitionItem.get(stockMovementItem?.id)
        removeShipmentAndPicklistItemsForModifiedRequisitionItem(requisitionItem)
    }

    void removeShipmentItemsForModifiedRequisitionItem(RequisitionItem requisitionItem) {

        // Get all shipment items associated with the given requisition item
        List<ShipmentItem> shipmentItems = ShipmentItem.findAllByRequisitionItem(requisitionItem)

        // Get all shipment items associated with the given requisition item's children
        requisitionItem?.requisitionItems?.each { RequisitionItem item ->
            shipmentItems.addAll(ShipmentItem.findAllByRequisitionItem(item))
        }

        // Delete all shipment items
        shipmentItems.each { ShipmentItem shipmentItem ->
            shipmentItem.delete()
        }
    }

    void removeShipmentAndPicklistItemsForModifiedRequisitionItem(RequisitionItem requisitionItem) {

        removeShipmentItemsForModifiedRequisitionItem(requisitionItem)

        // Find all picklist items associated with the given requisition item
        List<PicklistItem> picklistItems = PicklistItem.findAllByRequisitionItem(requisitionItem)

        // Find all picklist items associated with the given requisition item's children
        requisitionItem?.requisitionItems?.each { RequisitionItem item ->
            picklistItems.addAll(PicklistItem.findAllByRequisitionItem(item))
        }

        picklistItems.each { PicklistItem picklistItem ->
            picklistItem.synchronousRequired = Boolean.TRUE
            picklistItem.delete()
        }
    }

    void updateAdjustedItems(StockMovement stockMovement, String adjustedProductCode) {
        stockMovement?.lineItems?.each { StockMovementItem stockMovementItem ->
            if (stockMovementItem.productCode == adjustedProductCode) {
                removeShipmentAndPicklistItemsForModifiedRequisitionItem(stockMovementItem.requisitionItem)
                createPicklist(stockMovementItem)
                createMissingShipmentItem(stockMovementItem.requisitionItem)
            }
        }
    }

    Shipment createShipment(StockMovement stockMovement) {
        log.info "create shipment " + (new JSONObject(stockMovement.toJson())).toString(4)

        Requisition requisition = stockMovement.requisition

        validateRequisition(requisition)

        Shipment shipment = Shipment.findByRequisition(requisition)

        if (!shipment) {
            shipment = new Shipment()
        } else {
            createMissingShipmentItems(stockMovement.requisition, shipment)
            return shipment
        }

        shipment.requisition = stockMovement.requisition
        shipment.shipmentNumber = stockMovement.identifier

        shipment.origin = stockMovement.origin
        shipment.destination = stockMovement.destination
        shipment.description = stockMovement.description

        // These values need defaults since they are not set until step 6
        shipment.expectedShippingDate = new Date()

        // Set default shipment type so we can save to the database without user input
        shipment.shipmentType = ShipmentType.get(Constants.DEFAULT_SHIPMENT_TYPE_ID)

        shipment.name = stockMovement.generateName()

        if (shipment.hasErrors() || !shipment.save(flush: true)) {
            throw new ValidationException("Invalid shipment", shipment.errors)
        }

        return shipment
    }

    Shipment updateShipment(StockMovement stockMovement) {
        if (stockMovement.requisition) {
            return updateShipmentForRequisitionBasedStockMovement(stockMovement)
        }
        else {
            return updateShipmentForShipmentBasedStockMovement(stockMovement)
        }


    }

    Shipment updateShipmentForShipmentBasedStockMovement(StockMovement stockMovement) {
        log.info "update inbound shipment " + (new JSONObject(stockMovement.toJson())).toString(4)
        Shipment shipment = Shipment.get(stockMovement.id)
        if (!shipment) {
            throw new IllegalArgumentException("Could not find shipment for stock movement with ID ${stockMovement.id}")
        }

        if (stockMovement.statusCode == StockMovementStatusCode.DISPATCHED.toString()) {
            String inventoryLocationName = locationService.getReceivingLocationName(stockMovement.identifier)
            Location inventoryLocation = locationService.findInternalLocation(shipment.destination, inventoryLocationName)
            if (inventoryLocation != null) {
                if (stockMovement.currentStatus == ShipmentStatusCode.PARTIALLY_RECEIVED.toString()) {
                    throw new IllegalArgumentException("You can not change destination if shipment is partially received!")
                }
                if (stockMovement.destination.organization == null) {
                    throw new IllegalArgumentException("This destination does not have organization assigned")
                }
                inventoryLocation.parentLocation = stockMovement.destination
                inventoryLocation.save(flush: true, failOnError: true)
            }
        }

        shipment.additionalInformation = stockMovement.comments
        shipment.shipmentType = stockMovement.shipmentType
        shipment.driverName = stockMovement.driverName
        shipment.expectedDeliveryDate = stockMovement.expectedDeliveryDate
        if (stockMovement.comments) {
            shipment.addToComments(new Comment(comment: stockMovement.comments))
        }
        if (shipment.destination != stockMovement.destination) {
            shipment.name = stockMovement.generateName()
            shipment.destination = stockMovement.destination
        }

        createOrUpdateTrackingNumber(shipment, stockMovement.trackingNumber)
        shipment.save()
    }

    Shipment updateShipmentForRequisitionBasedStockMovement(StockMovement stockMovement) {
        log.info "update outbound shipment " + (new JSONObject(stockMovement.toJson())).toString(4)

        Shipment shipment = Shipment.findByRequisition(stockMovement.requisition)

        if (!shipment) {
            throw new IllegalArgumentException("Could not find shipment for stock movement with ID ${stockMovement.id}")
        }

        if (stockMovement.statusCode == StockMovementStatusCode.DISPATCHED.toString()) {
            String inventoryLocationName = locationService.getReceivingLocationName(stockMovement.identifier)
            Location inventoryLocation = locationService.findInternalLocation(shipment.destination, inventoryLocationName)
            if (inventoryLocation != null) {
                if (stockMovement.currentStatus == ShipmentStatusCode.PARTIALLY_RECEIVED.toString()) {
                    throw new IllegalArgumentException("You can not change destination if shipment is partially received!")
                }
                if (stockMovement.destination.organization == null) {
                    throw new IllegalArgumentException("This destination does not have organization assigned")
                }
                inventoryLocation.parentLocation = stockMovement.destination
                inventoryLocation.save(flush: true, failOnError: true)
            }
        }

        if (stockMovement.requisition.status == RequisitionStatus.ISSUED) {
            shipment.name = shipment.description == stockMovement.description && shipment.destination == stockMovement.destination ? stockMovement.name : stockMovement.generateName()

            if (shipment.destination != stockMovement.destination) {
                shipment.outgoingTransactions?.each { transaction ->
                    transaction.destination = stockMovement.destination
                    transaction.save()
                }
            }
        } else {
            shipment.name = stockMovement.generateName()
        }

        shipment.origin = stockMovement.origin
        shipment.destination = stockMovement.destination
        shipment.description = stockMovement.description
        shipment.additionalInformation = stockMovement.comments
        shipment.driverName = stockMovement.driverName
        shipment.expectedShippingDate = stockMovement.dateShipped ?: shipment.expectedShippingDate
        shipment.expectedDeliveryDate = stockMovement.expectedDeliveryDate ?: shipment.expectedDeliveryDate
        shipment.shipmentType = stockMovement.shipmentType ?: shipment.shipmentType

        createOrUpdateTrackingNumber(shipment, stockMovement.trackingNumber)

        if (shipment.hasErrors() || !shipment.save(flush: true)) {
            throw new ValidationException("Invalid shipment", shipment.errors)
        }

        updateRequisitionOnShipmentChange(stockMovement)

        return shipment
    }


    ReferenceNumber createOrUpdateTrackingNumber(Shipment shipment, String trackingNumber) {
        ReferenceNumberType trackingNumberType = ReferenceNumberType.findById(Constants.TRACKING_NUMBER_TYPE_ID)
        if (!trackingNumberType) {
            throw new IllegalStateException("Must configure reference number type for Tracking Number with ID '${Constants.TRACKING_NUMBER_TYPE_ID}'")
        }

        // Needed to use ID since reference numbers is lazy loaded and equality operation was not working
        ReferenceNumber referenceNumber = shipment.referenceNumbers.find { ReferenceNumber refNum ->
            trackingNumberType?.id?.equals(refNum.referenceNumberType?.id)
        }

        if (trackingNumber) {
            // Create a new reference number
            if (!referenceNumber) {
                referenceNumber = new ReferenceNumber()
                referenceNumber.identifier = trackingNumber
                referenceNumber.referenceNumberType = trackingNumberType
                shipment.addToReferenceNumbers(referenceNumber)
            }
            // Update the existing reference number
            else {
                referenceNumber.identifier = trackingNumber
            }
        }
        // Reference number exists but the user-defined tracking number was empty so we should delete
        else if (referenceNumber) {
            shipment.removeFromReferenceNumbers(referenceNumber)
        }
        return referenceNumber
    }

    Shipment updateShipmentOnRequisitionChange(StockMovement stockMovement) {
        Shipment shipment = Shipment.findByRequisition(stockMovement.requisition)

        if (!shipment) {
            throw new IllegalArgumentException("Could not find shipment for stock movement with ID ${stockMovement.id}")
        }

        shipment.origin = stockMovement.origin
        shipment.destination = stockMovement.destination
        shipment.description = stockMovement.description
        shipment.name = stockMovement.generateName()

        if (shipment.hasErrors() || !shipment.save(flush: true)) {
            throw new ValidationException("Invalid shipment", shipment.errors)
        }

        return shipment
    }

    ShipmentItem createOrUpdateShipmentItem(RequisitionItem requisitionItem) {

        ShipmentItem shipmentItem = ShipmentItem.findByRequisitionItem(requisitionItem)

        if (!shipmentItem) {
            shipmentItem = new ShipmentItem()
        }

        InventoryItem inventoryItem = inventoryService.findOrCreateInventoryItem(requisitionItem.product,
                requisitionItem.lotNumber, requisitionItem.expirationDate)

        shipmentItem.requisitionItem = requisitionItem
        shipmentItem.product = requisitionItem.product
        shipmentItem.inventoryItem = inventoryItem
        shipmentItem.lotNumber = inventoryItem.lotNumber
        shipmentItem.expirationDate = inventoryItem.expirationDate
        shipmentItem.quantity = requisitionItem.quantity
        shipmentItem.recipient = requisitionItem.recipient
        shipmentItem.sortOrder = requisitionItem.orderIndex
        return shipmentItem
    }


    Container createOrUpdateContainer(Shipment shipment, String palletName, String boxName) {
        if (boxName && !palletName) {
            throw new IllegalArgumentException("Please enter Pack level 1 before Pack level 2. A box must be contained within a pallet")
        }

        Container pallet = (palletName) ? shipment.findOrCreatePallet(palletName) : null
        Container box = (boxName) ? pallet.findOrCreateBox(boxName) : null
        return box ?: pallet ?: null
    }

    void createMissingShipmentItems(StockMovement stockMovement) {
        Requisition requisition = stockMovement.requisition.refresh()

        if (requisition) {
            Shipment shipment = Shipment.findByRequisition(requisition)
            if (shipment && requisition.status >= RequisitionStatus.PICKED) {
                createMissingShipmentItems(requisition, shipment)

                if (shipment.hasErrors() || !shipment.save(flush: true)) {
                    throw new ValidationException("Invalid shipment", shipment.errors)
                }
            }
        }
    }

    void createMissingShipmentItems(Requisition requisition, Shipment shipment) {
        if (requisition.origin.isSupplier() || !requisition.origin.supports(ActivityCode.MANAGE_INVENTORY)) {
            requisition.requisitionItems?.each { RequisitionItem requisitionItem ->
                Container container = createOrUpdateContainer(shipment, requisitionItem.palletName, requisitionItem.boxName)
                ShipmentItem shipmentItem = createOrUpdateShipmentItem(requisitionItem)
                shipmentItem.container = container
                shipment.addToShipmentItems(shipmentItem)
            }
        } else {
            requisition.requisitionItems?.each { RequisitionItem requisitionItem ->
                List<ShipmentItem> shipmentItems = createShipmentItems(requisitionItem)

                shipmentItems.each { ShipmentItem shipmentItem ->
                    shipment.addToShipmentItems(shipmentItem)
                }
            }
        }
    }

    void createMissingShipmentItem(RequisitionItem requisitionItem) {
        Requisition requisition = requisitionItem.requisition

        if (requisition) {
            Shipment shipment = Shipment.findByRequisition(requisition)

            if (shipment && requisition.status >= RequisitionStatus.PICKED) {
                List<ShipmentItem> shipmentItems = createShipmentItems(requisitionItem)

                shipmentItems.each { ShipmentItem shipmentItem ->
                    shipment.addToShipmentItems(shipmentItem)
                }

                if (shipment.hasErrors() || !shipment.save(flush: true)) {
                    throw new ValidationException("Invalid shipment", shipment.errors)
                }
            }
        }
    }

    List<ShipmentItem> createShipmentItems(RequisitionItem requisitionItem) {
        List<ShipmentItem> shipmentItems = new ArrayList<ShipmentItem>()

        if (ShipmentItem.findAllByRequisitionItem(requisitionItem)) {
            return shipmentItems
        }

        requisitionItem?.picklistItems?.each { PicklistItem picklistItem ->
            if (picklistItem.quantity > 0) {
                ShipmentItem shipmentItem = new ShipmentItem()
                shipmentItem.lotNumber = picklistItem?.inventoryItem?.lotNumber
                shipmentItem.expirationDate = picklistItem?.inventoryItem?.expirationDate
                shipmentItem.product = picklistItem?.inventoryItem?.product
                shipmentItem.quantity = picklistItem?.quantity
                shipmentItem.requisitionItem = picklistItem.requisitionItem
                shipmentItem.recipient = picklistItem?.requisitionItem?.recipient ?:
                        picklistItem?.requisitionItem?.parentRequisitionItem?.recipient
                shipmentItem.inventoryItem = picklistItem?.inventoryItem
                shipmentItem.binLocation = picklistItem?.binLocation
                shipmentItem.sortOrder = shipmentItems.size()

                shipmentItems.add(shipmentItem)
            }
        }

        requisitionItem?.requisitionItems?.each { item ->
            shipmentItems.addAll(createShipmentItems(item))
        }

        return shipmentItems
    }

    List<PackPageItem> updatePackPageItems(List<PackPageItem> packPageItems) {
        if (packPageItems) {
            packPageItems.each { PackPageItem packPageItem ->
                updateShipmentItemAndProcessSplitLines(packPageItem)
            }
        }

        return packPageItems
    }

    void updateShipmentItemAndProcessSplitLines(PackPageItem packPageItem) {
        ShipmentItem shipmentItem = ShipmentItem.get(packPageItem?.shipmentItemId)

        if (packPageItem?.splitLineItems && shipmentItem) {
            PackPageItem item = packPageItem.splitLineItems.pop()
            shipmentItem.quantity = item?.quantityShipped
            shipmentItem.recipient = item?.recipient
            shipmentItem.container = createOrUpdateContainer(shipmentItem.shipment, item?.palletName, item?.boxName)
            shipmentItem.save(flush: true)

            for (PackPageItem splitLineItem : packPageItem.splitLineItems) {
                ShipmentItem splitItem = new ShipmentItem()
                splitItem.requisitionItem = shipmentItem.requisitionItem
                splitItem.shipment = shipmentItem.shipment
                splitItem.product = shipmentItem.product
                splitItem.lotNumber = shipmentItem.lotNumber
                splitItem.expirationDate = shipmentItem.expirationDate
                splitItem.binLocation = shipmentItem.binLocation
                splitItem.inventoryItem = shipmentItem.inventoryItem
                splitItem.sortOrder = shipmentItem.sortOrder

                splitItem.quantity = splitLineItem?.quantityShipped
                splitItem.recipient = splitLineItem?.recipient
                splitItem.container = createOrUpdateContainer(shipmentItem.shipment, splitLineItem?.palletName, splitLineItem?.boxName)

                splitItem.shipment.addToShipmentItems(splitItem)
                splitItem.save(flush: true)
            }
        } else if (shipmentItem) {
            shipmentItem.quantity = packPageItem?.quantityShipped
            shipmentItem.recipient = packPageItem?.recipient
            shipmentItem.container = createOrUpdateContainer(shipmentItem.shipment, packPageItem?.palletName, packPageItem?.boxName)
            shipmentItem.save(flush: true)
        }
    }

    void issueShipmentBasedStockMovement(String id) {
        User user = AuthService.currentUser.get()
        StockMovement stockMovement = getStockMovement(id)
        Shipment shipment = stockMovement.shipment
        if (!shipment) {
            throw new IllegalStateException("There are no shipments associated with stock movement ${stockMovement.id}")
        }
        shipmentService.sendShipment(shipment, "Sent on ${new Date()}", user, shipment.origin, stockMovement.dateShipped ?: new Date())
    }

    void issueRequisitionBasedStockMovement(String id) {

        User user = AuthService.currentUser.get()
        StockMovement stockMovement = getStockMovement(id)
        Requisition requisition = stockMovement.requisition
        def shipment = requisition.shipment

        validateRequisition(requisition)

        if (!shipment) {
            throw new IllegalStateException("There are no shipments associated with stock movement ${requisition.requestNumber}")
        }

        shipmentService.sendShipment(shipment, null, user, requisition.origin, stockMovement.dateShipped ?: new Date())
    }

    void validateRequisition(Requisition requisition) {

        requisition.requisitionItems.each { requisitionItem ->
            if (!requisition.origin.isSupplier() && requisition.origin.supports(ActivityCode.MANAGE_INVENTORY) && requisition.status > RequisitionStatus.CREATED) {
                validateRequisitionItem(requisitionItem)
            }
        }
    }

    void validateRequisitionItem(RequisitionItem requisitionItem) {
        // check if there is picklist created for each item that has status different than canceled, substituted or changed
        if (!requisitionItem.picklistItems && !(requisitionItem.status in [RequisitionItemStatus.CANCELED, RequisitionItemStatus.SUBSTITUTED, RequisitionItemStatus.CHANGED])) {
            throw new ValidationException("There is picklist missing for item " + requisitionItem.product.productCode + " " + requisitionItem.product.name, requisitionItem.errors)
        } else if (requisitionItem.picklistItems) {
            // if there is picklist created check if quantity picked is equal to quantity requested if there was no reason code given(items canceled during pick or picked partially have reason code)
            if (requisitionItem.totalQuantityPicked() != requisitionItem.quantity && !requisitionItem.picklistItems.reasonCode) {
                throw new ValidationException("Please change the pick qty for item " + requisitionItem.product.productCode + " " + requisitionItem.product.name + " or enter reason code.", requisitionItem.errors)
            }
        }
    }


    void rollbackStockMovement(String id) {
        StockMovement stockMovement = getStockMovement(id)

        // If the shipment has been shipped we can roll it back
        Requisition requisition = stockMovement?.requisition
        Shipment shipment = stockMovement?.requisition?.shipment ?: stockMovement?.shipment
        if (shipment && shipment.currentStatus > ShipmentStatusCode.PENDING) {
            shipmentService.rollbackLastEvent(shipment)
            if (requisition) {
                requisitionService.rollbackRequisition(requisition)
            }
        }
    }

    void synchronizeStockMovement(String id, Date dateShipped) {

        StockMovement stockMovement = getStockMovement(id)

        // Legacy requisition that needs a shipment
        Requisition requisition = stockMovement.requisition
        Shipment shipment = stockMovement?.requisition?.shipment ?: stockMovement?.shipment
        Transaction outboundTransaction = stockMovement.requisition.transactions.find { it.transactionType?.transactionCode == TransactionCode.DEBIT }
        if (requisition && outboundTransaction && !stockMovement?.shipment) {
            shipment = createShipment(stockMovement)
            shipment.expectedShippingDate = dateShipped
            createMissingShipmentItems(requisition, shipment)
            shipmentService.createShipmentEvent(shipment, dateShipped, EventCode.SHIPPED, stockMovement.origin)
            outboundTransaction.outgoingShipment = shipment
            return
        }
        // Outbound stock movement created through workflow
        else {
            // Otherwise we have a stock movement likely with an empty shipment and transaction
            if (shipment?.outgoingTransactions?.size() > 1) {
                throw new IllegalStateException("Cannot synchronize a stock movement that has more than 1 transactions")
            }

            if (!shipment) {
                shipment = createShipment(stockMovement)
                shipment.expectedShippingDate = dateShipped
            }
            createMissingShipmentItems(stockMovement)

            if (!shipment.hasShipped()) {
                shipmentService.createShipmentEvent(shipment, dateShipped, EventCode.SHIPPED, stockMovement.origin)
            }

            outboundTransaction = shipment.outgoingTransactions ?
                    shipment.outgoingTransactions.iterator().next() : null
            if (outboundTransaction) {
                shipmentService.updateOutboundTransaction(outboundTransaction, shipment)
            } else {
                shipmentService.createOutboundTransaction(shipment)
            }
        }
    }

    Boolean isSynchronizationAuthorized(StockMovement stockMovement) {
        if (!stockMovement?.requisition) {
            throw new IllegalStateException("Stock movement ${stockMovement?.id} must be an outbound stock movement")
        }
        if(stockMovement.requisition?.status != RequisitionStatus.ISSUED) {
            throw new IllegalStateException("Stock movement ${stockMovement?.id} has not been issued")
        }
        if (stockMovement?.requisition?.picklist?.picklistItems?.size() <= 0) {
            throw new IllegalStateException("Stock movement ${stockMovement?.id} must have a picklist with more than 1 item")
        }
        if (stockMovement?.shipment?.shipmentItems?.size() > 0) {
            throw new IllegalStateException("Stock movement ${stockMovement?.id} must not have any shipment items")
        }
        if (stockMovement?.shipment?.outgoingTransactions?.transactionEntries?.flatten()?.size() > 0) {
            throw new IllegalStateException("Stock movement ${stockMovement?.id} must not have any transaction entries")
        }
        return true
    }


    List<Map> getDocuments(StockMovement stockMovement) {
        def g = grailsApplication.mainContext.getBean('org.codehaus.groovy.grails.plugins.web.taglib.ApplicationTagLib')
        def documentList = []

        if (stockMovement?.requisition) {
            documentList.addAll([
                    [
                            name        : g.message(code: "stockMovement.exportStockMovementItems.label", default: "Export Stock Movement Items"),
                            documentType: DocumentGroupCode.EXPORT.name(),
                            contentType : "text/csv",
                            stepNumber  : 2,
                            uri         : g.createLink(controller: 'stockMovement', action: "exportCsv", id: stockMovement?.requisition?.id, absolute: true),
                            hidden      : false
                    ],
                    [
                            name        : g.message(code: "picklist.button.print.label"),
                            documentType: DocumentGroupCode.PICKLIST.name(),
                            contentType : "text/html",
                            stepNumber  : 4,
                            uri         : g.createLink(controller: 'picklist', action: "print", id: stockMovement?.requisition?.id, absolute: true)
                    ],
                    [
                            name        : g.message(code: "picklist.button.download.label"),
                            documentType: DocumentGroupCode.PICKLIST.name(),
                            contentType : "application/pdf",
                            stepNumber  : 4,
                            uri         : g.createLink(controller: 'picklist', action: "renderPdf", id: stockMovement?.requisition?.id, absolute: true),
                            hidden      : true
                    ],
                    [
                            name        : g.message(code: "deliveryNote.label", default: "Delivery Note"),
                            documentType: DocumentGroupCode.DELIVERY_NOTE.name(),
                            contentType : "text/html",
                            stepNumber  : 5,
                            uri         : g.createLink(controller: 'deliveryNote', action: "print", id: stockMovement?.requisition?.id, absolute: true)
                    ]
            ])
        }

        if (stockMovement?.shipment) {
            documentList.addAll([
                    [
                            name        : g.message(code: "shipping.exportPackingList.label"),
                            documentType: DocumentGroupCode.PACKING_LIST.name(),
                            contentType : "application/vnd.ms-excel",
                            stepNumber  : 5,
                            uri         : g.createLink(controller: 'shipment', action: "exportPackingList", id: stockMovement?.shipment?.id, absolute: true),
                            hidden      : false
                    ],
                    [
                            name        : g.message(code: "shipping.downloadPackingList.label"),
                            documentType: DocumentGroupCode.PACKING_LIST.name(),
                            contentType : "application/vnd.ms-excel",
                            stepNumber  : 5,
                            uri         : g.createLink(controller: 'doc4j', action: "downloadPackingList", id: stockMovement?.shipment?.id, absolute: true)
                    ],
                    [
                            name        : g.message(code: "shipping.downloadCertificateOfDonation.label"),
                            documentType: DocumentGroupCode.CERTIFICATE_OF_DONATION.name(),
                            contentType : "application/vnd.ms-excel",
                            stepNumber  : 5,
                            uri         : g.createLink(controller: 'doc4j', action: "downloadCertificateOfDonation", id: stockMovement?.shipment?.id, absolute: true)
                    ],
                    [
                            name        : g.message(code: "goodsReceiptNote.label"),
                            documentType: DocumentGroupCode.GOODS_RECEIPT_NOTE.name(),
                            contentType : "text/html",
                            stepNumber  : null,
                            uri         : g.createLink(controller: 'goodsReceiptNote', action: "print", id: stockMovement?.shipment?.id, absolute: true),
                            hidden      : !stockMovement?.shipment?.receipt
                    ]
            ])
        }

        if (stockMovement?.shipment) {
            ShipmentWorkflow shipmentWorkflow = shipmentService.getShipmentWorkflow(stockMovement?.shipment)
            log.info "Shipment workflow " + shipmentWorkflow
            if (shipmentWorkflow) {
                shipmentWorkflow.documentTemplates.each { Document documentTemplate ->
                    documentList << [
                            name        : documentTemplate?.name,
                            documentType: documentTemplate?.documentType?.name,
                            contentType : documentTemplate?.contentType,
                            stepNumber  : null,
                            uri         : documentTemplate?.fileUri ?: g.createLink(controller: 'document', action: "render",
                                    id: documentTemplate?.id, params: [shipmentId: stockMovement?.shipment?.id],
                                    absolute: true, title: documentTemplate?.filename),
                            fileUri    : documentTemplate?.fileUri
                    ]
                }
            }

            stockMovement?.shipment?.documents.each { Document document ->
                def action = document.documentType?.documentCode == DocumentCode.SHIPPING_TEMPLATE ? "render" : "download"
                documentList << [
                        id          : document?.id,
                        name        : document?.name,
                        documentType: document?.documentType?.name,
                        contentType : document?.contentType,
                        stepNumber  : null,
                        uri         : document?.fileUri ?: g.createLink(controller: 'document', action: action,
                                id: document?.id, params: [shipmentId: stockMovement?.shipment?.id],
                                absolute: true, title: document?.filename),
                        fileUri    : document?.fileUri
                ]
            }

        }

        return documentList
    }

    List buildStockMovementItemList(StockMovement stockMovement) {
        // We need to create at least one row to ensure an empty template
        if (stockMovement?.lineItems?.empty) {
            stockMovement?.lineItems.add(new StockMovementItem())
        }

        def lineItems = stockMovement.lineItems.collect {
            [
                "Requisition item id"            : it?.id ?: "",
                "Product code (required)"     : it?.product?.productCode ?: "",
                "Product name"                  : it?.product?.name ?: "",
                "Pack level 1"                   : it?.palletName ?: "",
                "Pack level 2"                      : it?.boxName ?: "",
                "Lot number"                    : it?.lotNumber ?: "",
                "Expiration date (MM/dd/yyyy)": it?.expirationDate ? it?.expirationDate?.format("MM/dd/yyyy") : "",
                "Quantity (required)"        : it?.quantityRequested ?: "",
                "Recipient id"                  : it?.recipient?.id ?: ""
            ]
        }
        return lineItems
    }

    def getDisabledMessage(StockMovement stockMovement, Location currentLocation, Boolean isEditing = false) {
        def g = grailsApplication.mainContext.getBean('org.codehaus.groovy.grails.plugins.web.taglib.ApplicationTagLib')

        boolean isSameOrigin = stockMovement?.origin?.id == currentLocation?.id
        boolean isSameDestination = stockMovement?.destination?.id == currentLocation?.id
        boolean isDepot = stockMovement?.origin?.isDepot()

        if ((stockMovement?.hasBeenReceived() || stockMovement?.hasBeenPartiallyReceived()) && isEditing) {
            return g.message(code: "stockMovement.cantEditReceived.message")
        } else if (!isSameOrigin && isDepot && stockMovement?.isPending() && !stockMovement?.isElectronicType()
         || (!isDepot && !isSameDestination && isEditing)) {
            return g.message(code: "stockMovement.isDifferentOrigin.message")
        } else if (stockMovement?.hasBeenReceived()) {
            return g.message(code: "stockMovement.hasAlreadyBeenReceived.message", args: [stockMovement?.identifier])
        } else if (!(stockMovement?.hasBeenShipped() || stockMovement?.hasBeenPartiallyReceived())) {
            return g.message(code: "stockMovement.hasNotBeenShipped.message", args: [stockMovement?.identifier])
        } else if (!stockMovement?.hasBeenIssued() && !stockMovement?.isFromOrder) {
            return g.message(code: "stockMovement.hasNotBeenIssued.message", args: [stockMovement?.identifier])
        } else if (!isSameDestination) {
            return g.message(code: "stockMovement.isDifferentLocation.message")
        }
    }
}

