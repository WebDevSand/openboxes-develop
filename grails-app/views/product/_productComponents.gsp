<div id="productComponents">
    <div class="box">
        <h2>
            <warehouse:message code="product.billOfMaterials.label" default="Bill of Materials"/>
        </h2>

        <g:formRemote id="addProductComponent" name="addProductComponent"
                                      update="productComponents" onSuccess="onSuccess(data,textStatus)" onComplete="onComplete()"
                                      url="[controller: 'product', action:'addProductComponent']">
            <table>
                <thead>
                <tr>
                    <th>
                        <warehouse:message code="product.productCode.label"/>
                    </th>
                    <th>
                        <warehouse:message code="product.name.label"/>
                    </th>
                    <th>
                        <warehouse:message code="productComponent.quantity.label" default="Quantity"/>
                    </th>
                    <th>
                        <warehouse:message code="productComponent.unitOfMeasure.label" default="Unit of Measure"/>
                    </th>
                    <th>
                        <warehouse:message code="productComponent.cost.label" default="Cost"/>
                    </th>
                    <th>
                        <warehouse:message code="default.actions.label" default="Actions"/>
                    </th>
                </tr>
                </thead>
                <tbody>
                <g:each var="productComponent" in="${productInstance.productComponents }" status="status">
                    <tr class="${status%2?'even':'odd'}">
                        <td>
                            ${productComponent?.componentProduct?.productCode }
                        </td>
                        <td>
                            ${productComponent?.componentProduct?.name }
                        </td>
                        <td>
                            ${productComponent?.quantity }
                        </td>
                        <td>
                            ${productComponent?.unitOfMeasure?.code }
                        </td>
                        <td>
                            <g:formatNumber number="${productComponent?.componentProduct?.pricePerUnit?:0.0}" />
                            ${grailsApplication.config.openboxes.locale.defaultCurrencyCode}
                        </td>
                        <td>
                            <g:remoteLink controller="product" action="deleteProductComponent" id="${productComponent.id }" class="button"
                                          update="productComponents" onSuccess="onSuccess(data,textStatus)" onComplete="onComplete()">
                                <img src="${createLinkTo(dir:'images/icons/silk', file:'delete.png')}" />&nbsp;
                                <warehouse:message code="default.button.delete.label" args="[warehouse.message(code:'package.label')]"/>
                            </g:remoteLink>
                        </td>
                    </tr>
                </g:each>
                <g:unless test="${productInstance?.productComponents }">
                    <tr>
                        <td colspan="6">
                            <div class="padded fade center">
                                <!-- empty -->
                            </div>
                        </td>
                    </tr>
                </g:unless>
                </tbody>

                <tfoot>
                    <tr>
                        <td colspan="2">
                            <input name="assemblyProduct.id" type="hidden" value="${productInstance?.id}" />
                            <g:autoSuggest id="componentProduct" name="componentProduct" size="80" class="medium text"
                                           placeholder="${warehouse.message(code:'product.addProductComponent.label', default: 'Search for a product to add as a component')}"
                                           jsonUrl="${request.contextPath }/json/findProductByName" width="500" styleClass="text"/>
                        </td>
                        <td>
                            <input type="text" name="quantity" placeholder="Quantity" class="medium text"/>
                        </td>
                        <td>
                            <g:select name="unitOfMeasure"
                                      from="${org.pih.warehouse.core.UnitOfMeasure.list() }"
                                      optionKey="id" optionValue="name"
                                      data-placeholder="Choose a unit of measure"
                                      class="chzn-select-deselect"
                                      noSelection="['null':'']"></g:select>
                        </td>
                        <td></td>
                        <td>
                            <button  class="button">
                                <img src="${createLinkTo(dir:'images/icons/silk', file:'add.png')}" />&nbsp;
                                ${warehouse.message(code:'default.button.add.label')}
                            </button>
                        </td>
                    </tr>
                </tfoot>
            </table>
        </g:formRemote>
    </div>
</div>
<script>
    function onSuccess() {
        $("#productComponents").focus();
    }

    function onComplete() {
        $("#productComponents").focus();
    }
</script>
