<%@ page import="org.pih.warehouse.core.EntityTypeCode; org.pih.warehouse.product.Attribute" %>
<g:set var="availableAttributes" value="${org.pih.warehouse.product.Attribute.findAllByActive(true)}"/>
<g:set var="availableAttributes" value="${availableAttributes.findAll { it.entityTypeCode == EntityTypeCode.PRODUCT_SUPPLIER}}"/>
<g:set var="colspan" value="${(availableAttributes?.size()?:0) + 12}"/>
<div class="box">
    <h2>
        <warehouse:message code="product.productSuppliers.label" default="Product Sources"/>
    </h2>

    <div class="dialog">
        <table>
            <thead>
            <g:sortableColumn property="code" title="${warehouse.message(code: 'default.code.label', default: 'Code')}" />

            <th><g:message code="default.name.label" default="Name" /></th>

            <th><g:message code="productSupplier.productCode.label" /></th>

            <th>
                <g:message code="productSupplier.supplier.label" default="Supplier" /> /
                <g:message code="productSupplier.supplierCode.label" default="Supplier Code" />
            </th>

            <th>
                <g:message code="productSupplier.manufacturer.label" default="Manufacturer" /> /
                <g:message code="productSupplier.manufacturerCode.label" default="Manufacturer Code" />
            </th>

            <th><g:message code="productSupplier.ratingTypeCode.label" default="Rating Type" /></th>

            <th><g:message code="unitOfMeasure.label" default="Unit of Measure" /></th>

            <th><g:message code="productPackage.price.label" default="Package Price" /></th>

            <th><g:message code="product.pricePerUnit.label" default="Unit Price" /></th>

            <g:each var="attribute" in="${availableAttributes}">
                <th>${attribute.name} (${attribute.code})</th>
            </g:each>

            <th><g:message code="default.actions.label" default="Actions" /></th>

            </thead>
            <tbody>
                <g:if test="${productInstance?.productSuppliers}">

                    <g:each var="productSupplier" in="${productInstance?.productSuppliers.sort()}" status="status">

                        <g:set var="defaultProductPackage" value="${productSupplier.defaultProductPackage}"/>

                        <tr class="prop ${status%2==0?'odd':'even'}">
                            <td>${fieldValue(bean: productSupplier, field: "code")?:g.message(code:'default.none.label')}</td>

                            <td>${fieldValue(bean: productSupplier, field: "name")?:g.message(code:'default.none.label')}</td>

                            <td>${fieldValue(bean: productSupplier, field: "productCode")?:g.message(code:'default.none.label')}</td>

                            <td>
                                ${fieldValue(bean: productSupplier, field: "supplier")}
                                ${fieldValue(bean: productSupplier, field: "supplierCode")}
                            </td>

                            <td>
                                ${fieldValue(bean: productSupplier, field: "manufacturer")}
                                ${fieldValue(bean: productSupplier, field: "manufacturerCode")}
                            </td>


                            <td>${fieldValue(bean: productSupplier, field: "ratingTypeCode")}</td>

                            <td>
                                <g:if test="${defaultProductPackage}">
                                    ${fieldValue(bean: defaultProductPackage?.uom, field: "code")}/${fieldValue(bean: defaultProductPackage, field: "quantity")}
                                </g:if>
                            </td>
                            <td>
                                <g:if test="${defaultProductPackage?.productPrice}">
                                    <g:hasRoleFinance onAccessDenied="${g.message(code:'errors.blurred.message', args: ['0.00'])}">
                                        ${fieldValue(bean: defaultProductPackage.productPrice, field: "price")}
                                        ${grailsApplication.config.openboxes.locale.defaultCurrencyCode}
                                    </g:hasRoleFinance>
                                </g:if>
                            </td>
                            <td>
                                <g:if test="${defaultProductPackage?.productPrice && defaultProductPackage?.quantity}">
                                    <g:hasRoleFinance onAccessDenied="${g.message(code:'errors.blurred.message', args: ['0.00'])}">
                                        ${defaultProductPackage?.productPrice?.price/defaultProductPackage?.quantity}
                                        ${grailsApplication.config.openboxes.locale.defaultCurrencyCode}
                                    </g:hasRoleFinance>
                                </g:if>
                            </td>

                            <g:each var="attribute" in="${availableAttributes}">
                                <td>
                                    <g:each var="productAttribute" in="${productSupplier.attributes.find { it.attribute.id == attribute.id }}">
                                        ${productAttribute.value}
                                        ${productAttribute?.unitOfMeasure?.code}
                                    </g:each>
                                </td>
                            </g:each>
                            <td>
                                <div class="button-group">
                                    <a href="javascript:void(0);" class="btn-show-dialog button"
                                       data-position="top"
                                       data-title="${g.message(code:'productSupplier.label')}"
                                       data-url="${request.contextPath}/productSupplier/dialog?id=${productSupplier?.id}&product.id=${productInstance?.id}">
                                        <img src="${createLinkTo(dir:'images/icons/silk', file:'pencil.png')}" />
                                        <g:message code="default.button.edit.label"/>
                                    </a>
                                    <g:link controller="productSupplier" action="delete" id="${productSupplier?.id}" params="[dialog:true]" class="button">
                                        <img src="${createLinkTo(dir:'images/icons/silk', file:'delete.png')}" />
                                        <g:message code="default.button.delete.label"/>
                                    </g:link>
                                </div>
                            </td>
                        </tr>
                    </g:each>
                </g:if>
                <g:unless test="${productInstance?.productSuppliers}">
                    <tr class="prop">
                        <td class="padded empty center" colspan="${colspan}">
%{--                            <g:message code="productSuppliers.empty.label" default="There are no product suppliers"/>--}%
                        </td>
                    </tr>
                </g:unless>
            </tbody>
            <tfoot>
            <tr>
                <td colspan="${colspan}">
                    <div class="center">
                        <button class="button btn-show-dialog" data-position="top"
                                data-title="${g.message(code: 'default.add.label', args: [g.message(code:'productSupplier.label')])}"
                                data-url="${request.contextPath}/productSupplier/dialog?product.id=${productInstance?.id}">
                            <img src="${createLinkTo(dir:'images/icons/silk', file:'add.png')}" />
                            ${g.message(code: 'default.create.label', default: 'Create', args: [g.message(code:'productSupplier.label')])}
                        </button>
                        <g:link class="button" controller="productSupplier" action="export" params="['productSupplier.id':productInstance?.productSuppliers*.id, format: 'xls']">
                            <img src="${createLinkTo(dir:'images/icons/silk', file:'page_excel.png')}" />
                            ${g.message(code: 'default.export.label', default: 'Export', args: [g.message(code:'productSuppliers.label')])}
                        </g:link>
                    </div>
                </td>
            </tr>
            </tfoot>
        </table>
    </div>
</div>


<g:javascript>
    $(document).ready(function() {
        $(".tabs").livequery(function() {
            $(this).tabs({});
        });
    });
</g:javascript>
