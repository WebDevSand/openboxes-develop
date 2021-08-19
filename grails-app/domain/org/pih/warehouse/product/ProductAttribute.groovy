/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.product

import org.pih.warehouse.core.UnitOfMeasure

/**
 * Represents the value of a particular Attribute for a particular Product
 */
class ProductAttribute {

    String id
    Attribute attribute
    String value

    ProductSupplier productSupplier

    UnitOfMeasure unitOfMeasure

    static belongsTo = [product: Product]

    static mapping = {
        id generator: 'uuid'
    }

    static constraints = {
        attribute(nullable: false)
        value(maxSize: 255)
        unitOfMeasure(nullable: true)
        productSupplier(nullable: true)
    }

    static PROPERTIES = [
            "productCode"   : "product.productCode",
            "attributeCode" : "attribute.code",
            "attributeValue": "value",
            "unitOfMeasure": "unitOfMeasure.code"
    ]

    static SUPPLIER_PROPERTIES = [
            "productCode"   : "product.productCode",
            "productSupplierCode" : "productSupplier.code",
            "attributeCode" : "attribute.code",
            "attributeValue": "value",
            "unitOfMeasure": "unitOfMeasure.code"
    ]

}
