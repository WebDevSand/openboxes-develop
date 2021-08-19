/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.core

import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import org.pih.warehouse.product.ProductSupplier

import java.util.regex.Matcher
import java.util.regex.Pattern
import org.apache.commons.lang.RandomStringUtils
import org.apache.commons.lang.StringUtils
import org.apache.commons.lang.WordUtils
import org.apache.commons.text.StringSubstitutor
import org.codehaus.groovy.grails.commons.ConfigurationHolder
import org.hibernate.ObjectNotFoundException
import org.pih.warehouse.inventory.Transaction
import org.pih.warehouse.order.Order
import org.pih.warehouse.product.Product
import org.pih.warehouse.product.ProductType
import org.pih.warehouse.receiving.Receipt
import org.pih.warehouse.requisition.Requisition
import org.pih.warehouse.shipping.Shipment

class IdentifierService {

    boolean transactional = true
    def grailsApplication
    def dataService
    def productTypeService

    /**
     * A: alphabetic
     * L: letter
     * N: numeric
     * D: digit
     * 0-9: digit
     *
     * @param format
     * @return
     */
    def generateIdentifier(String format) {
        if (!format || format.isEmpty()) {
            println "format must be specified"
            throw new IllegalArgumentException("Format pattern string must be specified")
        }

        String identifier = ""
        for (int i = 0; i < format.length(); i++) {
            switch (format[i]) {
                case 'N':
                    identifier += RandomStringUtils.random(1, grailsApplication.config.openboxes.identifier.numeric)
                    break
                case 'D':
                    identifier += RandomStringUtils.random(1, grailsApplication.config.openboxes.identifier.numeric)
                    break
                case 'L':
                    identifier += RandomStringUtils.random(1, grailsApplication.config.openboxes.identifier.alphabetic)
                    break
                case 'A':
                    identifier += RandomStringUtils.random(1, grailsApplication.config.openboxes.identifier.alphanumeric)
                    break
                default:
                    identifier += format[i]
            }
        }

        return identifier
    }

    /**
     * Generate a random identifier of given length using alphanumeric characters.
     *
     * @param length
     */
    def generateIdentifier(int length) {
        return RandomStringUtils.random(length, grailsApplication.config.openboxes.identifier.alphanumeric)
    }

    String generateInvoiceIdentifier() {
        return generateIdentifier(grailsApplication.config.openboxes.identifier.invoice.format)
    }

    def generateOrderIdentifier() {
        return generateIdentifier(grailsApplication.config.openboxes.identifier.order.format)
    }

    def generatePurchaseOrderIdentifier() {
        return generateIdentifier(grailsApplication.config.openboxes.identifier.purchaseOrder.format)
    }

    def generateProductIdentifier(String format) {
        if (StringUtils.isNotBlank(format)) {
            return generateIdentifier(format)
        }

        return generateIdentifier(grailsApplication.config.openboxes.identifier.product.format)
    }

    def generateProductIdentifier() {
        return generateIdentifier(grailsApplication.config.openboxes.identifier.product.format)
    }

    def generateProductSupplierIdentifier() {
        return generateProductSupplierIdentifier(null)
    }

    def generateProductSupplierIdentifier(String prefix) {
        String identifier = generateIdentifier(grailsApplication.config.openboxes.identifier.productSupplier.format)
        Boolean prefixEnabled = grailsApplication.config.openboxes.identifier.productSupplier.prefix.enabled
        if (prefixEnabled && prefix) {
            identifier = "${prefix}${Constants.DEFAULT_NAME_SEPARATOR}${identifier}"
        }
        return identifier
    }

    def generateProductSupplierIdentifier(String prefix, String suffix) {
        if (prefix && suffix) {
            if (ProductSupplier.findByCode("${prefix}${Constants.DEFAULT_NAME_SEPARATOR}${suffix}")) {
                return generateProductSupplierIdentifier("${prefix}${Constants.DEFAULT_NAME_SEPARATOR}${suffix}")
            }
            return "${prefix}${Constants.DEFAULT_NAME_SEPARATOR}${suffix}"
        } else if (prefix) {
            return generateProductSupplierIdentifier(prefix)
        } else {
            return generateIdentifier(grailsApplication.config.openboxes.identifier.productSupplier.format)
        }
    }

    def generateRequisitionIdentifier() {
        return generateIdentifier(grailsApplication.config.openboxes.identifier.requisition.format)
    }

    def generateShipmentIdentifier() {
        return generateIdentifier(grailsApplication.config.openboxes.identifier.shipment.format)
    }

    def generateReceiptIdentifier() {
        return generateIdentifier(grailsApplication.config.openboxes.identifier.receipt.format)
    }

    def generateTransactionIdentifier() {
        return generateIdentifier(grailsApplication.config.openboxes.identifier.transaction.format)
    }

    def generateOrganizationIdentifier() {
        return generateIdentifier(grailsApplication.config.openboxes.identifier.organization.format)
    }

    def generateOrganizationIdentifier(String name) {
        Integer minSize = ConfigurationHolder.config.openboxes.identifier.organization.minSize
        Integer maxSize = ConfigurationHolder.config.openboxes.identifier.organization.maxSize

        // Clean up string by removing everything after command
        name = name.split(",")[0].capitalize()
        String identifier = WordUtils.initials(name)?.replaceAll("[^a-zA-Z0-9]", "")
        if (identifier.length() < minSize) {
            identifier = WordUtils.abbreviate(name, minSize, maxSize, null)
        }
        else if (identifier.length() > maxSize) {
            identifier = identifier.substring(0, maxSize)
        }
        return identifier.toUpperCase()
    }

    def extractSequenceFormat(String productIdentifierFormat, String sequentialPatternChar, Integer allowedSequences) {
        Pattern pattern = Pattern.compile("${sequentialPatternChar}+")
        Matcher matcher = pattern.matcher(productIdentifierFormat)
        int count = 0
        def sequenceFormat = ""

        while (matcher.find()) {
            sequenceFormat = matcher.group()
            count++
        }

        // If custom identifier contains more than one sequential part, then throw exception
        if (count > allowedSequences) {
            throw new IllegalArgumentException("Cannot have more sequence numbers than ${allowedSequences} in the same identifier")
        }

        return sequenceFormat
    }

    def generateSequentialIdentifier(String identifierFormat, String sequenceFormat, String sequenceNumber) {
        List identifierFormatComponents
        List identifierComponents

        // Split custom identifier by sequence format (with keeping sequence inside identifier components)
        String sequenceDelimiter = "((?<=${sequenceFormat})|(?=${sequenceFormat}))"
        identifierFormatComponents = identifierFormat.split(sequenceDelimiter)
        identifierComponents = identifierFormatComponents.collect { String identifierFormatComponent ->
            if (identifierFormatComponent.contains("0")) {
                return generateSequenceNumber(sequenceNumber.toString(), identifierFormatComponent)
            }
            else {
                return generateIdentifier(identifierFormatComponent)
            }
        }

        return identifierComponents.join("")
    }

    def generateSequentialProductIdentifier(ProductType productType) {
        def sequenceFormat = extractSequenceFormat(productType.productIdentifierFormat, Constants.DEFAULT_SEQUENCE_NUMBER_FORMAT_CHAR, 1)
        def sequenceNumber = productTypeService.getAndSetNextSequenceNumber(productType)

        return generateSequentialIdentifier(productType.productIdentifierFormat, sequenceFormat, sequenceNumber.toString())
    }

    String generateSequentialProductIdentifierFromCode(ProductType productType) {
        if (!productType || !productType.code) {
            throw new IllegalArgumentException("Missing product type or code")
        }

        Integer sequenceNumber = productTypeService.getAndSetNextSequenceNumber(productType)
        String sequenceNumberStr = generateSequenceNumber(sequenceNumber.toString())

        String template = ConfigurationHolder.config.openboxes.identifier.productCode.format
        String delimiter = ConfigurationHolder.config.openboxes.identifier.productCode.delimiter
        Map properties = ConfigurationHolder.config.openboxes.identifier.productCode.properties
        Map model = dataService.transformObject(productType, properties)
        model.put("sequenceNumber", sequenceNumberStr)
        model.put("delimiter", delimiter)
        return renderTemplate(template, model)
    }

    String generateProductIdentifier(ProductType productType) {
        // If the product type is null, then generate product code from DEFAULT_PRODUCT_NUMBER_FORMAT
        if (!productType) {
            return generateProductIdentifier()
        }

        // If the product type does not have a custom identifier but has code, then generate sequential product code
        if (!productType.productIdentifierFormat && productType.code) {
            return generateSequentialProductIdentifierFromCode(productType)
        }

        // If the product type does not have a custom identifier, then generate product code from DEFAULT_PRODUCT_NUMBER_FORMAT
        if (!productType.productIdentifierFormat) {
            return generateProductIdentifier()
        }

        // if does not contain sequential part, then generate identifier basing on custom format
        if (!productType.productIdentifierFormat.contains("0")) {
            return generateIdentifier(productType.productIdentifierFormat)
        }

        return generateSequentialProductIdentifier(productType)
    }

    def generateSequenceNumber(String sequenceNumber, String sequenceNumberFormat) {
        return StringUtils.leftPad(sequenceNumber, sequenceNumberFormat.length(), sequenceNumberFormat.substring(0, 1))
    }

    def generateSequenceNumber(String sequenceNumber) {
        String sequenceNumberFormat = ConfigurationHolder.config.openboxes.identifier.sequenceNumber.format
        return generateSequenceNumber(sequenceNumber, sequenceNumberFormat)
    }

    def renderTemplate(String template, Map model) {
        return StringSubstitutor.replace(template, model)
    }

    void assignTransactionIdentifiers() {
        def transactions = Transaction.findAll("from Transaction as t where transactionNumber is null or transactionNumber = ''")
        transactions.each { transaction ->
            try {
                println "Assigning identifier to transaction " + transaction.id + " " + transaction.dateCreated + " " + transaction.lastUpdated
                Transaction.withTransaction {
                    transaction.transactionNumber = generateTransactionIdentifier()
                    if (!transaction.merge(flush: true, validate: false)) {
                        println transaction.errors
                    }
                }
                println "Assigned identifier to transaction " + transaction.id + " " + transaction.dateCreated + " " + transaction.lastUpdated
            } catch (ObjectNotFoundException e) {
                println("Unable to assign identifier to transaction with ID " + transaction?.id + ": " + e.message)

            } catch (Exception e) {
                println("Unable to assign identifier to transaction with ID " + transaction?.id + ": " + e.message)
            }
        }
    }


    void assignProductIdentifiers() {
        def products = Product.findAll("from Product as p where p.active = true and (p.productCode is null or p.productCode = '')")
        products.each { product ->
            try {
                def productCode = generateProductIdentifier()
                println "Assigning identifier ${productCode} to product " + product.id + " " + product.name

                // Check to see if there's already a product with that product code
                if (!Product.findByProductCode(productCode)) {
                    product.productCode = productCode
                    if (!product.merge(flush: true, validate: false)) {
                        println product.errors
                    }
                }
            } catch (MySQLIntegrityConstraintViolationException e) {
                log.warn("Unable to assign identifier due to constraint violation: " + e.message, e)
            } catch (Exception e) {
                log.warn("Unable to assign identifier to product with ID " + product?.id + ": " + e.message, e)
            }
        }
    }

    void assignShipmentIdentifiers() {
        def shipments = Shipment.findAll("from Shipment as s where shipmentNumber is null or shipmentNumber = ''")
        shipments.each { shipment ->
            println "Assigning identifier to shipment " + shipment.id + " " + shipment.name
            try {
                shipment.shipmentNumber = generateShipmentIdentifier()
                if (!shipment.merge(flush: true, validate: false)) {
                    println shipment.errors
                }
            } catch (Exception e) {
                println("Unable to assign identifier to shipment with ID " + shipment?.id + ": " + e.message)
            }
        }
    }

    void assignReceiptIdentifiers() {
        def receipts = Receipt.findAll("from Receipt as s where receiptNumber is null or receiptNumber = ''")
        receipts.each { Receipt receipt ->
            println "Assigning identifier to receipt " + receipt.id
            try {
                receipt.receiptNumber = generateReceiptIdentifier()
                if (!receipt.merge(flush: true, validate: false)) {
                    println receipt.errors
                }
            } catch (Exception e) {
                println("Unable to assign identifier to receipt with ID " + receipt?.id + ": " + e.message)
            }
        }
    }


    void assignRequisitionIdentifiers() {
        def requisitions = Requisition.findAll("from Requisition as r where (requestNumber is null or requestNumber = '') and (isTemplate is null or isTemplate = false)")
        requisitions.each { requisition ->
            try {
                println "Assigning identifier to requisition " + requisition.id + " " + requisition.name
                requisition.requestNumber = generateRequisitionIdentifier()
                if (!requisition.merge(flush: true, validate: false)) {
                    println requisition.errors
                }
            } catch (Exception e) {
                println("Unable to assign identifier to requisition with ID " + requisition?.id + ": " + e.message)
            }
        }
    }

    void assignOrderIdentifiers() {
        def orders = Order.findAll("from Order as o where orderNumber is null or orderNumber = ''")
        orders.each { order ->
            try {
                println "Assigning identifier to order " + order.id + " " + order.name
                order.orderNumber = generateOrderIdentifier()
                if (!order.merge(flush: true, validate: false)) {
                    println order.errors
                }
            } catch (Exception e) {
                println("Unable to assign identifier to order with ID " + order?.id + ": " + e.message)
            }
        }
    }


}
