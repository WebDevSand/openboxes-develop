/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/

package org.pih.warehouse.invoice

import org.pih.warehouse.core.Location
import org.pih.warehouse.order.Order

import java.text.SimpleDateFormat

class InvoiceController {
    def invoiceService

    // This template is generated by webpack during application start
    def index = {
        redirect(action: "create", params: params)
    }

    def create = {
        render(template: "/common/react", params: params)
    }

    def list = {
        params.max = params.max?:10
        params.offset = params.offset?:0
        params.createdBy = params.createdBy ?: null
        def dateFormat = new SimpleDateFormat("MM/dd/yyyy")
        params.dateInvoiced = params.dateInvoiced ? dateFormat.parse(params.dateInvoiced) : null
        params.invoiceNumber = params.invoiceNumber ?: ""
        def location = Location.get(session.warehouse.id)
        params.partyFromId = location?.organization?.id
        def invoices = invoiceService.listInvoices(params)

        [
                invoices         : invoices,
        ]
    }

    def show = {
        def invoiceInstance = Invoice.get(params.id)
        if (!invoiceInstance) {
            flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'invoice.label', default: 'Invoice'), params.id])}"
            redirect(action: "list")
        } else {
            [invoiceInstance: invoiceInstance]
        }
    }

    def rollback = {
        def invoiceInstance = Invoice.get(params.id)
        if (!invoiceInstance) {
            flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'invoice.label', default: 'Invoice'), params.id])}"
            redirect(action: "list")
        } else {
            invoiceInstance.datePosted = null

            if (params.refreshInvoice) {
                invoiceService.refreshInvoiceItems(invoiceInstance)
            }

            invoiceInstance.save()
            flash.message = "${warehouse.message(code: 'invoices.successfulRollback.message')}"
            redirect(action: "show", id: params.id)
        }
    }

    def eraseInvoice = {
        def invoiceInstance = Invoice.get(params.id)
        if (invoiceInstance) {
            try {
                invoiceInstance.delete(flush: true)
                flash.message = "${warehouse.message(code: 'default.deleted.message', args: [warehouse.message(code: 'invoice.label', default: 'Invoice'), params.id])}"
                redirect(action: "list")
            }
            catch (org.springframework.dao.DataIntegrityViolationException e) {
                flash.message = "${warehouse.message(code: 'default.not.deleted.message', args: [warehouse.message(code: 'invoice.label', default: 'Invoice'), params.id])}"
                redirect(action: "list", id: params.id)
            }
        } else {
            flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'invoice.label', default: 'Invoice'), params.id])}"
            redirect(action: "list")
        }
        redirect(action: "list", id: params.id)
    }

    def generatePrepaymentInvoice = {
        Order order = Order.get(params.id)
        if (!order) {
            flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'order.label', default: 'Order'), params.id])}"
            redirect(action: "list")
        }

        Invoice invoice = invoiceService.generatePrepaymentInvoice(order)
        redirect(action: "create", params: [id: invoice.id])
    }

    def generateInvoice = {
        Order order = Order.get(params.id)
        if (!order) {
            flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'order.label', default: 'Order'), params.id])}"
            redirect(action: "list")
        }

        if (!order.activeOrderItems && !order.activeOrderAdjustments) {
            flash.message = "${warehouse.message(code: 'invoices.emptyOrder.message')}"
            redirect(controller: "order", action: "create", params: [id: order.id])
        }

        Invoice invoice = invoiceService.generateInvoice(order)
        redirect(action: "create", params: [id: invoice.id])
    }
}