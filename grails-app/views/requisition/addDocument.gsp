<html>
<head>
	<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
	<meta name="layout" content="custom" />
	<g:set var="entityName" value="${warehouse.message(code: 'request.label', default: 'Request')}" />
	<title><warehouse:message code="default.edit.label" args="[entityName]" /></title>
</head>

<body>

	<div class="body">
		<g:if test="${flash.message}">
			<div class="message">
				${flash.message}
			</div>
		</g:if>
		<g:hasErrors bean="${requestInstance}">
			<div class="errors">
				<g:renderErrors bean="${requestInstance}" as="list" />
			</div>
		</g:hasErrors>
			

		<fieldset>
			<g:render template="summary" />
			<div>
				<!-- process an upload or save depending on whether we are adding a new doc or modifying a previous one -->					
				<g:uploadForm controller="document" action="${documentInstance?.id ? 'saveDocument' : 'uploadDocument'}">
					<g:hiddenField name="requestId" value="${requestInstance?.id}" />
					<g:hiddenField name="documentId" value="${documentInstance?.id}" />					
					<table>
						<tbody>
							<tr class="prop">
								<td valign="top" class="name"><label><warehouse:message
									code="document.selectFile.label"/></label>
								</td>
								<td valign="top"
									class="value ${hasErrors(bean: documentInstance, field: 'fileContents', 'errors')}">
									<!-- determine if this is an add or an edit -- at this point you can only edit document details, not modify the file itself -->
									<g:if test="${!documentInstance?.id}">
										<input name="fileContents" type="file" />
									</g:if>
									<g:else>
										${documentInstance?.filename}
									</g:else>
								</td>
							</tr>
							<tr class="prop">
								<td valign="top" class="name"><label class="optional"><warehouse:message
									code="default.description.label" default="Description" /></label>
								</td>
								<td valign="top"
									class="value ${hasErrors(bean: documentInstance, field: 'name', 'errors')}">
									<g:textField name="name" value="${documentInstance?.name}" />
								</td>
							</tr>
							<tr class="prop">
								<td valign="top" class="name"><label><warehouse:message
									code="document.type.label"/></label></td>
								<td valign="top"
									class="value ${hasErrors(bean: documentInstance, field: 'documentType', 'errors')}">
												<g:select name="typeId" from="${org.pih.warehouse.core.DocumentType.list().sort{it.name}}"
														  value="${documentInstance?.documentType?.id}"
													noSelection="['': warehouse.message(code:'document.chooseDocumentType.label')]"
														  optionKey="id" optionValue="${{format.metadata(obj:it)}}"/>
								</td>
							</tr>
							<tr class="prop">
								<td valign="top" class="name"><label class="optional"><warehouse:message
									code="document.number.label" /></label>
								</td>
								<td valign="top"
									class="value ${hasErrors(bean: documentInstance, field: 'documentNumber', 'errors')}">
									<g:textField name="documentNumber" value="${documentInstance?.documentNumber}" />
								</td>
							</tr>

						</tbody>
						<tfoot>
							<tr class="prop">
								<td valign="top" class="name"></td>
								<td valign="top" class="value">
									<div class="buttons left">
										<!-- show upload or save depending on whether we are adding a new doc or modifying a previous one -->
										<button type="submit" class="positive">
											<img src="${createLinkTo(dir:'images/icons/silk',file:'tick.png')}" alt="save" />${documentInstance?.id ? warehouse.message(code:'default.button.save.label') : warehouse.message(code:'default.button.upload.label')}
										</button>
										<g:link controller="requisition" action="show" id="${requestInstance?.id}" class="negative">
											<img src="${createLinkTo(dir:'images/icons/silk',file:'cancel.png')}" alt="Cancel" /> <warehouse:message
												code="default.button.cancel.label" /> </g:link>
									</div>
								</td>
							</tr>
						</tfoot>
					</table>
				</g:uploadForm>
			</div>
		</fieldset>
	</div>
</body>
</html>
