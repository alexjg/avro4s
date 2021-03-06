package com.sksamuel.avro4s

import org.apache.avro.SchemaBuilder

object BigDecimals {
  implicit object AsString extends SchemaFor[BigDecimal] {
    override def schema(implicit namingStrategy: NamingStrategy) = SchemaBuilder.builder().stringType()
  }
}