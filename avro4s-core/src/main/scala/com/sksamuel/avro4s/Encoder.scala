package com.sksamuel.avro4s

import java.nio.ByteBuffer
import java.sql.{Date, Timestamp}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}
import java.util.UUID

import magnolia.{CaseClass, Magnolia, SealedTrait}
import org.apache.avro.LogicalTypes.Decimal
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericData.EnumSymbol
import org.apache.avro.util.Utf8
import org.apache.avro.{Conversions, Schema}
import shapeless.{:+:, CNil, Coproduct, Inl, Inr}

import scala.language.experimental.macros
import scala.math.BigDecimal.RoundingMode
import scala.math.BigDecimal.RoundingMode.RoundingMode

/**
  * An [[Encoder]] encodes a Scala value of type T into a compatible
  * Avro value based on the given schema.
  *
  * For example, given a string, and a schema of type Schema.Type.STRING
  * then the string would be encoded as an instance of Utf8, whereas
  * the same string and a Schema.Type.FIXED would be encoded as an
  * instance of GenericData.Fixed.
  *
  * Another example is given a Scala enumeration value, and a schema of
  * type Schema.Type.ENUM, the value would be encoded as an instance
  * of GenericData.EnumSymbol.
  */
trait Encoder[T] extends Serializable {
  self =>

  def encode(t: T, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef

  def comap[S](fn: S => T): Encoder[S] = new Encoder[S] {
    override def encode(value: S, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = self.encode(fn(value), schema)
  }
}

case class Exported[A](instance: A) extends AnyVal

object Encoder {

  def apply[T](implicit encoder: Encoder[T]): Encoder[T] = encoder

  implicit object StringEncoder extends Encoder[String] {
    override def encode(value: String, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
      schema.getType match {
        case Schema.Type.FIXED => new GenericData.Fixed(schema, value.getBytes)
        case Schema.Type.BYTES => ByteBuffer.wrap(value.getBytes)
        case _ => new Utf8(value)
      }
    }
  }

  implicit object BooleanEncoder extends Encoder[Boolean] {
    override def encode(t: Boolean, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.lang.Boolean = java.lang.Boolean.valueOf(t)
  }

  implicit object IntEncoder extends Encoder[Int] {
    override def encode(t: Int, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.lang.Integer = java.lang.Integer.valueOf(t)
  }

  implicit object LongEncoder extends Encoder[Long] {
    override def encode(t: Long, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.lang.Long = java.lang.Long.valueOf(t)
  }

  implicit object FloatEncoder extends Encoder[Float] {
    override def encode(t: Float, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.lang.Float = java.lang.Float.valueOf(t)
  }

  implicit object DoubleEncoder extends Encoder[Double] {
    override def encode(t: Double, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.lang.Double = java.lang.Double.valueOf(t)
  }

  implicit object ShortEncoder extends Encoder[Short] {
    override def encode(t: Short, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.lang.Short = java.lang.Short.valueOf(t)
  }

  implicit object ByteEncoder extends Encoder[Byte] {
    override def encode(t: Byte, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.lang.Byte = java.lang.Byte.valueOf(t)
  }

  implicit object NoneEncoder extends Encoder[None.type] {
    override def encode(t: None.type, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy) = null
  }

  implicit val UUIDEncoder: Encoder[UUID] = StringEncoder.comap[UUID](_.toString)
  implicit val LocalTimeEncoder: Encoder[LocalTime] = IntEncoder.comap[LocalTime](lt => lt.toSecondOfDay * 1000 + lt.getNano / 1000)
  implicit val LocalDateEncoder: Encoder[LocalDate] = IntEncoder.comap[LocalDate](_.toEpochDay.toInt)
  implicit val InstantEncoder: Encoder[Instant] = LongEncoder.comap[Instant](_.toEpochMilli)
  implicit val LocalDateTimeEncoder: Encoder[LocalDateTime] = InstantEncoder.comap[LocalDateTime](_.toInstant(ZoneOffset.UTC))
  implicit val TimestampEncoder: Encoder[Timestamp] = InstantEncoder.comap[Timestamp](_.toInstant)
  implicit val DateEncoder: Encoder[Date] = LocalDateEncoder.comap[Date](_.toLocalDate)

  implicit def mapEncoder[V](implicit encoder: Encoder[V]): Encoder[Map[String, V]] = new Encoder[Map[String, V]] {

    import scala.collection.JavaConverters._

    override def encode(map: Map[String, V], schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.util.Map[String, AnyRef] = {
      require(schema != null)
      map.map { case (k, v) =>
        (k, encoder.encode(v, schema.getValueType))
      }.asJava
    }
  }

  implicit def listEncoder[T](implicit encoder: Encoder[T]): Encoder[List[T]] = new Encoder[List[T]] {

    import scala.collection.JavaConverters._

    override def encode(ts: List[T], schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.util.List[AnyRef] = {
      require(schema != null)
      val arraySchema = SchemaHelper.extractSchemaFromPossibleUnion(schema, Schema.Type.ARRAY)
      ts.map(encoder.encode(_, arraySchema.getElementType)).asJava
    }
  }

  implicit def setEncoder[T](implicit encoder: Encoder[T]): Encoder[Set[T]] = new Encoder[Set[T]] {

    import scala.collection.JavaConverters._

    override def encode(ts: Set[T], schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.util.List[AnyRef] = {
      require(schema != null)
      val arraySchema = SchemaHelper.extractSchemaFromPossibleUnion(schema, Schema.Type.ARRAY)
      ts.map(encoder.encode(_, arraySchema.getElementType)).toList.asJava
    }
  }

  implicit def vectorEncoder[T](implicit encoder: Encoder[T]): Encoder[Vector[T]] = new Encoder[Vector[T]] {

    import scala.collection.JavaConverters._

    override def encode(ts: Vector[T], schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.util.List[AnyRef] = {
      require(schema != null)
      val arraySchema = SchemaHelper.extractSchemaFromPossibleUnion(schema, Schema.Type.ARRAY)
      ts.map(encoder.encode(_, arraySchema.getElementType)).asJava
    }
  }

  implicit def seqEncoder[T](implicit encoder: Encoder[T]): Encoder[Seq[T]] = new Encoder[Seq[T]] {

    import scala.collection.JavaConverters._

    override def encode(ts: Seq[T], schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): java.util.List[AnyRef] = {
      require(schema != null)
      val arraySchema = SchemaHelper.extractSchemaFromPossibleUnion(schema, Schema.Type.ARRAY)
      ts.map(encoder.encode(_, arraySchema.getElementType)).asJava
    }
  }

  implicit object ByteArrayEncoder extends Encoder[Array[Byte]] {
    override def encode(t: Array[Byte], schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
      schema.getType match {
        case Schema.Type.FIXED => new GenericData.Fixed(schema, t)
        case Schema.Type.BYTES => ByteBuffer.wrap(t)
        case _ => sys.error(s"Unable to encode $t for schema $schema")
      }
    }
  }

  implicit val ByteListEncoder: Encoder[List[Byte]] = ByteArrayEncoder.comap(_.toArray[Byte])
  implicit val ByteSeqEncoder: Encoder[Seq[Byte]] = ByteArrayEncoder.comap(_.toArray[Byte])
  implicit val ByteVectorEncoder: Encoder[Vector[Byte]] = ByteArrayEncoder.comap(_.toArray[Byte])

  implicit object ByteBufferEncoder extends Encoder[ByteBuffer] {
    override def encode(t: ByteBuffer, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): ByteBuffer = t
  }

  implicit def arrayEncoder[T](implicit encoder: Encoder[T]): Encoder[Array[T]] = new Encoder[Array[T]] {

    import scala.collection.JavaConverters._

    // if our schema is BYTES then we assume the incoming array is a byte array and serialize appropriately
    override def encode(ts: Array[T], schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = schema.getType match {
      case Schema.Type.BYTES => ByteBuffer.wrap(ts.asInstanceOf[Array[Byte]])
      case _ => ts.map(encoder.encode(_, schema.getElementType)).toList.asJava
    }
  }

  implicit def optionEncoder[T](implicit encoder: Encoder[T]): Encoder[Option[T]] = new Encoder[Option[T]] {

    import scala.collection.JavaConverters._

    override def encode(t: Option[T], schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
      // if the option is none we just return null, otherwise we encode the value
      // by finding the non null schema
      val nonNullSchema = schema.getTypes.asScala.filter(_.getType != Schema.Type.NULL).toList match {
        case s :: Nil => s
        case multipleSchemas => Schema.createUnion(multipleSchemas.asJava)
      }
      t.map(encoder.encode(_, nonNullSchema)).orNull
    }
  }

  implicit def eitherEncoder[T, U](implicit leftEncoder: Encoder[T], rightEncoder: Encoder[U]): Encoder[Either[T, U]] = new Encoder[Either[T, U]] {
    override def encode(t: Either[T, U], schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = t match {
      case Left(left) => leftEncoder.encode(left, schema.getTypes.get(0))
      case Right(right) => rightEncoder.encode(right, schema.getTypes.get(1))
    }
  }

  private val decimalConversion = new Conversions.DecimalConversion

  implicit def bigDecimalEncoder(implicit roundingMode: RoundingMode = RoundingMode.UNNECESSARY): Encoder[BigDecimal] = new Encoder[BigDecimal] {

    import org.apache.avro.Conversions

    private val converter = new Conversions.DecimalConversion
    private val rm = java.math.RoundingMode.valueOf(roundingMode.id)

    override def encode(decimal: BigDecimal, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy) = {

      // we support encoding big decimals in three ways - fixed, bytes or as a String, depending on the schema passed in
      // the scale and precision should come from the schema and the rounding mode from the implicit
      schema.getType match {
        case Schema.Type.STRING => StringEncoder.encode(decimal.toString, schema)
        case Schema.Type.BYTES => ByteBufferEncoder.comap[BigDecimal] { value =>
          val logical = schema.getLogicalType.asInstanceOf[Decimal]
          converter.toBytes(decimal.underlying.setScale(logical.getScale, rm), schema, logical)
        }.encode(decimal, schema)
        case Schema.Type.FIXED =>
          val logical = schema.getLogicalType.asInstanceOf[Decimal]
          converter.toFixed(decimal.underlying.setScale(logical.getScale, rm), schema, logical)
        case _ => sys.error(s"Cannot serialize BigDecimal as ${schema.getType}")
      }
    }
  }

  implicit def javaEnumEncoder[E <: Enum[_]]: Encoder[E] = new Encoder[E] {
    override def encode(t: E, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): EnumSymbol = new EnumSymbol(schema, t.name)
  }

  implicit def scalaEnumEncoder[E <: Enumeration#Value]: Encoder[E] = new Encoder[E] {
    override def encode(t: E, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): EnumSymbol = new EnumSymbol(schema, t.toString)
  }

  type Typeclass[T] = Encoder[T]

  implicit def gen[T]: Typeclass[T] = macro Magnolia.gen[T]

  /**
    * Encodes a field in a case class by using a schema for the fields type.
    * The schema passed in here is the schema for the container type, and the fieldName
    * is the name of the field in the avro schema.
    *
    * Note: The field may be a member of a subclass of a trait, in which case
    * the schema passed in will be a union. Therefore we must extract the correct
    * subschema from the union. We can do this by using the fullName of the
    * containing class, and comparing to the record full names in the subschemas.
    *
    */
  private def encodeField[T](t: T, fieldName: String, schema: Schema, fullName: String, encoder: Encoder[T]): AnyRef = {
    schema.getType match {
      case Schema.Type.UNION =>
        val subschema = SchemaHelper.extractTraitSubschema(fullName, schema)
        val field = subschema.getField(fieldName)
        encoder.encode(t, field.schema)
      case Schema.Type.RECORD =>
        val field = schema.getField(fieldName)
        encoder.encode(t, field.schema)
      // otherwise we are encoding a simple field
      case _ => encoder.encode(t, schema)
    }
  }

  /**
    * Takes the encoded values from the fields of a type T and builds
    * an [[ImmutableRecord]] from them, using the given schema.
    *
    * The schema for a record must be of Type Schema.Type.RECORD but
    * the case class may have been a subclass of a trait. In this case
    * the schema will be a union and so we must extract the correct
    * subschema from the union.
    *
    * @param fullName the full name of the record in Avro, taking into
    *                 account Avro modifiers such as @AvroNamespace
    *                 and @AvroErasedName. This name is used for
    *                 extracting the specific subschema from a union schema.
    */
  def buildRecord(schema: Schema, values: Seq[AnyRef], fullName: String): AnyRef = {
    schema.getType match {
      case Schema.Type.UNION =>
        val subschema = SchemaHelper.extractTraitSubschema(fullName, schema)
        ImmutableRecord(subschema, values.toVector)
      case Schema.Type.RECORD =>
        ImmutableRecord(schema, values.toVector)
      case _ =>
        sys.error(s"Trying to encode a field from schema $schema which is neither a RECORD nor a UNION")
    }
  }

  def combine[T](klass: CaseClass[Typeclass, T]): Encoder[T] = {

    val extractor = new AnnotationExtractors(klass.annotations)
    val doc = extractor.doc.orNull
    val aliases = extractor.aliases
    val props = extractor.props

    val namer = Namer(klass.typeName, klass.annotations)
    val namespace = namer.namespace
    val name = namer.name

    // An encoder for a value type just needs to pass through the given value into an encoder
    // for the backing type. At runtime, the value type class won't exist, and the input
    // will be an instance of whatever the backing field of the value class was defined to be.
    // In other words, if you had a value type `case class Foo(str :String)` then the value
    // avro expects is a string, not a record of Foo, so the encoder for Foo should just encode
    // the underlying string
    if (klass.isValueClass) {
      new Encoder[T] {
        override def encode(t: T, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
          val p = klass.parameters.head
          p.typeclass.encode(p.dereference(t), schema)
        }
      }
    } else {
      new Encoder[T] {
        override def encode(t: T, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
          // the schema passed here must be a record since we are encoding a non-value case class
          require(schema.getType == Schema.Type.RECORD)
          val values = klass.parameters.flatMap { p =>
            val extractor = new AnnotationExtractors(p.annotations)
            if (extractor.transient) None else {
              // the name may have been overriden with @AvroName and we should then encode with the naming strategy
              val name = naming.to(extractor.name.getOrElse(p.label))
              val field = schema.getField(name)
              if (field == null) throw new RuntimeException(s"Expected field $name did not exist in the schema")
              Some(p.typeclass.encode(p.dereference(t), field.schema))
            }
          }
          buildRecord(schema, values.asInstanceOf[Seq[AnyRef]], name)
        }
      }
    }
  }

  def dispatch[T](ctx: SealedTrait[Typeclass, T]): Encoder[T] = new Encoder[T] {
    override def encode(t: T, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
      ctx.dispatch(t) { subtype =>
        val namer = Namer(subtype.typeName, subtype.annotations)
        val fullname = namer.namespace + "." + namer.name
        schema.getType match {
          // we support two types of schema here - a union when subtypes are classes and a enum when the subtypes are all case objects
          case Schema.Type.UNION =>
            // we need to extract the subschema matching the input type
            // note: that the schema may have a custom name and a custom namespace!
            // note2: the field for the ADT itself may be annotated!
            val a = ctx.annotations
            val namer = Namer(subtype.typeName, subtype.annotations)
            val subschema = SchemaHelper.extractTraitSubschema(namer.fullName, schema)
            subtype.typeclass.encode(t.asInstanceOf[subtype.SType], subschema)
          // for enums we just encode the type name in an enum symbol wrapper. simples!
          case Schema.Type.ENUM => new GenericData.EnumSymbol(schema, namer.name)
          case other => sys.error(s"Unsupported schema type $other for sealed traits")
        }
      }
    }
  }

  implicit def tuple2Encoder[A, B](implicit encA: Encoder[A], encB: Encoder[B]) = new Encoder[(A, B)] {
    override def encode(t: (A, B), schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
      ImmutableRecord(
        schema,
        Vector(
          encA.encode(t._1, schema.getField("_1").schema()),
          encB.encode(t._2, schema.getField("_2").schema()))
      )
    }
  }

  implicit def tuple3Encoder[A, B, C](implicit encA: Encoder[A], encB: Encoder[B], encC: Encoder[C]) = new Encoder[(A, B, C)] {
    override def encode(t: (A, B, C), schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
      ImmutableRecord(
        schema,
        Vector(
          encA.encode(t._1, schema.getField("_1").schema()),
          encB.encode(t._2, schema.getField("_2").schema()),
          encC.encode(t._3, schema.getField("_3").schema()))
      )
    }
  }

  implicit def tuple4Encoder[A, B, C, D](implicit encA: Encoder[A], encB: Encoder[B], encC: Encoder[C], encD: Encoder[D]) = new Encoder[(A, B, C, D)] {
    override def encode(t: (A, B, C, D), schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
      ImmutableRecord(
        schema,
        Vector(
          encA.encode(t._1, schema.getField("_1").schema()),
          encB.encode(t._2, schema.getField("_2").schema()),
          encC.encode(t._3, schema.getField("_3").schema()),
          encD.encode(t._4, schema.getField("_4").schema()))
      )
    }
  }

  implicit def tuple5Encoder[A, B, C, D, E](implicit encA: Encoder[A], encB: Encoder[B], encC: Encoder[C], encD: Encoder[D], encE: Encoder[E]) = new Encoder[(A, B, C, D, E)] {
    override def encode(t: (A, B, C, D, E), schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
      ImmutableRecord(
        schema,
        Vector(
          encA.encode(t._1, schema.getField("_1").schema()),
          encB.encode(t._2, schema.getField("_2").schema()),
          encC.encode(t._3, schema.getField("_3").schema()),
          encD.encode(t._4, schema.getField("_4").schema()),
          encE.encode(t._5, schema.getField("_5").schema()))
      )
    }
  }

  // A coproduct is a union, or a generalised either.
  // A :+: B :+: C :+: CNil is a type that is either an A, or a B, or a C.

  // Shapeless's implementation builds up the type recursively,
  // (i.e., it's actually A :+: (B :+: (C :+: CNil)))

  // `encode` here should never actually be invoked, because you can't
  // actually construct a value of type a: CNil, but the Encoder[CNil]
  // needs to exist to supply a base case for the recursion.
  implicit def cnilEncoder: Encoder[CNil] = new Encoder[CNil] {
    override def encode(t: CNil, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = sys.error("This should never happen: CNil has no inhabitants")
  }

  // A :+: B is either Inl(value: A) or Inr(value: B), continuing the recursion
  implicit def coproductEncoder[H, T <: Coproduct](implicit encoderS: Encoder[H], encoderT: Encoder[T]): Encoder[H :+: T] = new Encoder[H :+: T] {
    override def encode(value: H :+: T, schema: Schema)(implicit naming: NamingStrategy = DefaultNamingStrategy): AnyRef = {
      // the schema passed in here will be a union
      require(schema.getType == Schema.Type.UNION)
      value match {
        // we must extract the appropriate schema from the union when we hit the base left case
        case Inl(h) =>
          val namer = Namer(h.getClass)
          val s = SchemaHelper.extractTraitSubschema(namer.fullName, schema)
          encoderS.encode(h, s)
        case Inr(t) => encoderT.encode(t, schema)
      }
    }
  }
}
