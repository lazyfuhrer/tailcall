package tailcall.runtime.transcoder

import tailcall.runtime.ast.Blueprint
import tailcall.runtime.dsl.scala.Orc
import tailcall.runtime.dsl.scala.Orc._
import tailcall.runtime.remote._
import zio.schema.DynamicValue

trait Orc2Blueprint {
  def toType(t: Type, isNull: Boolean = true): Blueprint.Type = {
    val nonNull = !isNull
    t match {
      case Type.NonNull(ofType)  => toType(ofType, false)
      case Type.NamedType(name)  => Blueprint.NamedType(name, nonNull)
      case Type.ListType(ofType) => Blueprint.ListType(toType(ofType, isNull), nonNull)
    }
  }

  private def toInputValueDefinition(lField: LabelledField[Input]): TValid[String, Blueprint.InputValueDefinition] =
    for {
      ofType <- TValid.fromOption(lField.field.ofType) <> TValid.fail("Input type must be named")
    } yield Blueprint.InputValueDefinition(lField.name, toType(ofType), lField.field.definition.defaultValue)

  private def toResolver(lfield: LabelledField[Output]): Option[Remote[DynamicValue] => Remote[DynamicValue]] =
    lfield.field.definition.resolve match {
      case Resolver.Empty           => Option(_.path("value", lfield.name).toDynamic)
      case Resolver.FromFunction(f) => Option(f)
    }
  private def toFieldDefinition(lField: LabelledField[Output]): TValid[String, Blueprint.FieldDefinition]     = {
    for {
      ofType <- TValid.fromOption(lField.field.ofType) <> TValid.fail("Output type must be named")
      args   <- TValid.foreach(lField.field.definition.arguments)(toInputValueDefinition)
    } yield Blueprint.FieldDefinition(
      name = lField.name,
      ofType = toType(ofType),
      args = args,
      resolver = toResolver(lField).map(Remote.toLambda(_))
    )
  }

  private def run(o: Orc): TValid[String, Blueprint] = {
    val schemaDefinition = Blueprint
      .SchemaDefinition(query = o.query, mutation = o.mutation, subscription = o.subscription)

    for {
      objectDefinitions <- TValid.foreach(o.types.map {
        case Orc.Obj(name, FieldSet.InputSet(fields))  => toInputObjectTypeDefinition(name, fields)
        case Orc.Obj(name, FieldSet.OutputSet(fields)) => toObjectTypeDefinition(name, fields)
        case Orc.Obj(name, FieldSet.Empty)             => toObjectTypeDefinition(name, Nil)
      })(identity)
    } yield Blueprint(schemaDefinition :: objectDefinitions)
  }

  private def toObjectTypeDefinition(name: String, fields: List[LabelledField[Output]]) = {
    TValid.foreach(fields)(toFieldDefinition).map(Blueprint.ObjectTypeDefinition(name, _))
  }

  private def toInputObjectTypeDefinition(name: String, fields: List[LabelledField[Input]]) = {
    TValid.foreach(fields)(toInputValueDefinition).map(Blueprint.InputObjectTypeDefinition(name, _))
  }

  def toBlueprint(o: Orc): TValid[String, Blueprint] = run(o)
}