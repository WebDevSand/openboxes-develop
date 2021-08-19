<%@ page import="org.pih.warehouse.core.ActivityCode;" %>
<%@ page import="org.pih.warehouse.core.Constants;" %>
<%@ page import="org.pih.warehouse.order.OrderStatus;" %>
<%@ page import="org.pih.warehouse.order.OrderType;" %>
<%@ page import="org.pih.warehouse.order.OrderTypeCode;" %>
<div class="box">
    <h2><warehouse:message code="default.filters.label"/></h2>
    <g:form id="listForm" action="list" method="GET">
        <g:hiddenField name="type" value="${params.type}"/>
        <g:hiddenField name="max" value="${params.max ?: 10}"/>
        <div class="filter-list">
            <div class="filter-list-item">
                <label>${warehouse.message(code: 'default.search.label')}</label>
                <g:textField class="text" id="q" name="q" value="${params.q}" style="width:100%" placeholder="Search by order number or description"/>

            </div>
            <div class="filter-list-item">
                <label>${warehouse.message(code: 'order.orderTypeCode.label')}</label>
                <g:select id="orderType"
                          name="orderType"
                          from="${OrderType.list()}"
                          class="select2"
                          optionValue="name"
                          optionKey="code"
                          value="${params?.orderType}"
                          noSelection="['': '']"/>
            </div>
            <div class="filter-list-item">
                <label>${warehouse.message(code: 'order.status.label')}</label>
                <g:select id="status"
                          name="status"
                          from="${OrderStatus.list()}"
                          class="select2"
                          optionValue="${{ format.metadata(obj: it) }}"
                          value="${params.status}"
                          noSelection="['': '']"/>
            </div>
            <div class="filter-list-item">
                <label><warehouse:message code="order.origin.label"/></label>
                <g:selectLocationViaAjax id="origin"
                                         name="origin"
                                         class="ajaxSelect2"
                                         noSelection="['':'']"
                                         value="${params.origin}"
                                         data-ajax--url="${request.contextPath }/json/findLocations?activityCode=${org.pih.warehouse.core.ActivityCode.FULFILL_ORDER}"
                                         data-allow-clear="true"
                                         data-ajax--cache="true"/>
            </div>
            <g:if test="${params.orderType == OrderTypeCode.PURCHASE_ORDER.name()}">
                <div class="filter-list-item">
                    <label><warehouse:message code="order.destination.label"/></label>
                    <g:selectLocationViaAjax id="destination"
                                             name="destination"
                                             class="ajaxSelect2"
                                             noSelection="['':'']"
                                             value="${params.destination}"
                                             data-ajax--url="${request.contextPath }/json/findLocations?activityCode=${org.pih.warehouse.core.ActivityCode.RECEIVE_STOCK}"
                                             data-allow-clear="true"/>
                </div>
                <div class="filter-list-item">
                    <label><warehouse:message code="order.destinationParty.label"/></label>
                    <g:selectOrganization name="destinationParty"
                                          id="destinationParty"
                                          value="${params.destinationParty}"
                                          roleTypes="[org.pih.warehouse.core.RoleType.ROLE_BUYER]"
                                          noSelection="['':'']"
                                          class="chzn-select-deselect"
                                          disabled="${isCentralPurchasingEnabled}" />
                </div>
            </g:if>
            <g:elseif test="${params.orderType == Constants.PUTAWAY_ORDER}">
                <div class="filter-list-item">
                    <label><warehouse:message code="order.destination.label"/></label>
                    <g:selectLocationViaAjax id="destination"
                                             name="destination"
                                             class="ajaxSelect2"
                                             noSelection="['':'']"
                                             value="${params.destination}"
                                             data-ajax--url="${request.contextPath }/json/findLocations?activityCode=${org.pih.warehouse.core.ActivityCode.PLACE_ORDER}"
                                             data-allow-clear="true"/>
                </div>
            </g:elseif>
            <div class="filter-list-item">
                <label><warehouse:message code="order.orderedBy.label"/></label>
                <g:selectPersonViaAjax id="orderedBy"
                                         name="orderedBy"
                                         class="ajaxSelect2"
                                         noSelection="['':'']"
                                         value="${params.orderedBy}"
                                         data-allow-clear="true"
                                         data-ajax--url="${request.contextPath }/json/findPersonByName"
                                         data-ajax--cache="true"/>
            </div>
            <div class="filter-list-item">
                <label><warehouse:message code="order.createdBy.label"/></label>
                <g:selectPersonViaAjax id="createdBy"
                                         name="createdBy"
                                         class="ajaxSelect2"
                                         noSelection="['':'']"
                                         value="${params.createdBy}"
                                         data-allow-clear="true"
                                         data-ajax--url="${request.contextPath }/json/findPersonByName"
                                         data-ajax--cache="true"/>
            </div>
            <div class="filter-list-item">
                <label>
                    ${warehouse.message(code: 'default.lastUpdateAfter.label', default: 'Last updated after')}
                </label>
                <a href="javascript:void(0);" id="clearStartDate">Clear</a>
                <g:jqueryDatePicker id="statusStartDate"
                                    name="statusStartDate"
                                    placeholder="Start date"
                                    size="40"
                                    numberOfMonths="2"
                                    changeMonthAndYear="false"
                                    value="${params.statusStartDate}"
                                    format="MM/dd/yyyy"/>
            </div>

            <div class="filter-list-item">
                <label>${warehouse.message(code: 'default.lastUpdatedBefore.label', default: 'Last updated before')}</label>
                <a href="javascript:void(0);" id="clearEndDate">Clear</a>
                <g:jqueryDatePicker id="statusEndDate"
                                    name="statusEndDate"
                                    placeholder="End date"
                                    size="40"
                                    numberOfMonths="2"
                                    changeMonthAndYear="true"
                                    value="${params.statusEndDate}"
                                    format="MM/dd/yyyy"/>
            </div>
            <div class="filter-list-item buttons center">
                <button type="submit" class="button icon search" name="search" value="true">
                    <warehouse:message code="default.search.label"/>
                </button>
                <button name="format" value="csv" class="button">
                    <img src="${resource(dir: 'images/icons/silk', file: 'page_excel.png')}" />&nbsp;
                    <warehouse:message code="order.downloadOrderLineDetails.label" default="Download order line details"/>
                </button>
                <g:link controller="order" action="list" class="button icon reload">
                    <warehouse:message code="default.button.cancel.label"/>
                </g:link>
            </div>
        </div>
    </g:form>
</div>
