/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.linker.backend.javascript

import scala.annotation.switch

// Unimport default print and println to avoid invoking them by mistake
import scala.Predef.{print => _, println => _, _}

import java.io.Writer

import org.scalajs.ir
import ir.Position
import ir.Position.NoPosition
import ir.Printers.IndentationManager

import Trees._

/* Printers code was hand-optimized with raw performance in mind.
 * It was hand optimized using fastOptJS on examples/reversi and testsSuite/test
 * as performance benchmarks. JProfiler was used to measure the performance of
 * hotspots in this object.
 */
object Printers {

  class JSTreePrinter(protected val out: Writer) extends IndentationManager {

    def printTopLevelTree(tree: Tree): Unit = {
      tree match {
        case Skip() =>
          // do not print anything
        case tree: Block =>
          var rest = tree.stats
          while (rest.nonEmpty) {
            printTopLevelTree(rest.head)
            rest = rest.tail
          }
        case _ =>
          printStat(tree)
          if (shouldPrintSepAfterTree(tree))
            print(';')
          println()
      }
    }

    protected def shouldPrintSepAfterTree(tree: Tree): Boolean = tree match {
      case _:DocComment | _:FunctionDef | _:ClassDef => false
      case _                                         => true
    }

    protected def printRow(ts: List[Tree], start: Char, end: Char): Unit = {
      print(start)
      var rest = ts
      while (rest.nonEmpty) {
        print(rest.head)
        rest = rest.tail
        if (rest.nonEmpty)
          print(", ")
      }
      print(end)
    }

    protected def printBlock(tree: Tree): Unit = {
      print('{'); indent(); println()
      tree match {
        case tree: Block =>
          var rest = tree.stats
          while (rest.nonEmpty) {
            val x = rest.head
            rest = rest.tail
            printStat(x)
            if (rest.nonEmpty) {
              if (shouldPrintSepAfterTree(x))
                print(';')
              println()
            }
          }

        case _ =>
          printStat(tree)
      }
      undent(); println(); print('}')
    }

    protected def printSig(args: List[ParamDef], restParam: Option[ParamDef]): Unit = {
      print("(")
      var rem = args
      while (rem.nonEmpty) {
        print(rem.head)
        rem = rem.tail
        if (rem.nonEmpty || restParam.nonEmpty)
          print(", ")
      }

      restParam.foreach { p =>
        print("...")
        print(p)
      }

      print(") ")
    }

    protected def printArgs(args: List[Tree]): Unit =
      printRow(args, '(', ')')

    protected def printStat(tree: Tree): Unit =
      printTree(tree, isStat = true)

    protected def print(tree: Tree): Unit =
      printTree(tree, isStat = false)

    def printTree(tree: Tree, isStat: Boolean): Unit = {
      tree match {
        // Comments

        case DocComment(text) =>
          val lines = text.split("\n").toList
          if (lines.tail.isEmpty) {
            print("/** ")
            print(lines.head)
            print(" */")
          } else {
            print("/** ")
            print(lines.head)
            println()
            var rest = lines.tail
            while (rest.nonEmpty) {
              print(" *  ")
              print(rest.head)
              println()
              rest = rest.tail
            }
            print(" */")
          }

        // Definitions

        case VarDef(ident, optRhs) =>
          print("var ")
          print(ident)
          optRhs foreach { rhs =>
            print(" = ")
            print(rhs)
          }

        case Let(ident, mutable, optRhs) =>
          print(if (mutable) "let " else "const ")
          print(ident)
          optRhs foreach { rhs =>
            print(" = ")
            print(rhs)
          }

        case ParamDef(ident) =>
          print(ident)

        // Control flow constructs

        case Skip() =>
          print("/*<skip>*/")

        case tree: Block =>
          if (isStat)
            printBlock(tree)
          else
            printRow(tree.stats, '(', ')')

        case Labeled(label, body) =>
          print(label)
          print(": ")
          printBlock(body)

        case Assign(lhs, rhs) =>
          print(lhs)
          print(" = ")
          print(rhs)

        case Return(expr) =>
          print("return ")
          print(expr)

        case If(cond, thenp, elsep) =>
          if (isStat) {
            print("if (")
            print(cond)
            print(") ")
            printBlock(thenp)
            elsep match {
              case Skip() => ()
              case _: If =>
                print(" else ")
                printTree(elsep, isStat)
              case _ =>
                print(" else ")
                printBlock(elsep)
            }
          } else {
            print('(')
            print(cond)
            print(" ? ")
            print(thenp)
            print(" : ")
            print(elsep)
            print(')')
          }

        case While(cond, body, label) =>
          if (label.isDefined) {
            print(label.get)
            print(": ")
          }
          print("while (")
          print(cond)
          print(") ")
          printBlock(body)

        case DoWhile(body, cond, label) =>
          if (label.isDefined) {
            print(label.get)
            print(": ")
          }
          print("do ")
          printBlock(body)
          print(" while (")
          print(cond)
          print(')')

        case ForIn(lhs, obj, body) =>
          print("for (")
          print(lhs)
          print(" in ")
          print(obj)
          print(") ")
          printBlock(body)

        case For(init, guard, update, body) =>
          print("for (")
          print(init)
          print("; ")
          print(guard)
          print("; ")
          print(update)
          print(") ")
          printBlock(body)

        case TryFinally(TryCatch(block, errVar, handler), finalizer) =>
          print("try ")
          printBlock(block)
          print(" catch (")
          print(errVar)
          print(") ")
          printBlock(handler)
          print(" finally ")
          printBlock(finalizer)

        case TryCatch(block, errVar, handler) =>
          print("try ")
          printBlock(block)
          print(" catch (")
          print(errVar)
          print(") ")
          printBlock(handler)

        case TryFinally(block, finalizer) =>
          print("try ")
          printBlock(block)
          print(" finally ")
          printBlock(finalizer)

        case Throw(expr) =>
          print("throw ")
          print(expr)

        case Break(label) =>
          if (label.isEmpty) print("break")
          else {
            print("break ")
            print(label.get)
          }

        case Continue(label) =>
          if (label.isEmpty) print("continue")
          else {
            print("continue ")
            print(label.get)
          }

        case Switch(selector, cases, default) =>
          print("switch (")
          print(selector)
          print(") ")
          print('{')
          indent()
          var rest = cases
          while (rest.nonEmpty) {
            val next = rest.head
            rest = rest.tail
            println()
            print("case ")
            print(next._1)
            print(':')
            if (!next._2.isInstanceOf[Skip]) {
              print(' ')
              printBlock(next._2)
            }
          }

          default match {
            case Skip() =>
            case _ =>
              println()
              print("default: ")
              printBlock(default)
          }

          undent()
          println()
          print('}')

        case Debugger() =>
          print("debugger")

        // Expressions

        case New(ctor, args) =>
          def containsOnlySelectsFromAtom(tree: Tree): Boolean = tree match {
            case DotSelect(qual, _)     => containsOnlySelectsFromAtom(qual)
            case BracketSelect(qual, _) => containsOnlySelectsFromAtom(qual)
            case VarRef(_)              => true
            case This()                 => true
            case _                      => false // in particular, Apply
          }
          if (containsOnlySelectsFromAtom(ctor)) {
            print("new ")
            print(ctor)
          } else {
            print("new (")
            print(ctor)
            print(')')
          }
          printArgs(args)

        case DotSelect(qualifier, item) =>
          qualifier match {
            case _:IntLiteral | _:DoubleLiteral =>
              print("(")
              print(qualifier)
              print(")")
            case _ =>
              print(qualifier)
          }
          print(".")
          print(item)

        case BracketSelect(qualifier, item) =>
          print(qualifier)
          print('[')
          print(item)
          print(']')

        case Apply(fun, args) =>
          print(fun)
          printArgs(args)

        case ImportCall(arg) =>
          print("import(")
          print(arg)
          print(')')

        case NewTarget() =>
          print("new.target")

        case ImportMeta()  =>
          print("import.meta")

        case Spread(items) =>
          print("...")
          print(items)

        case Delete(prop) =>
          print("delete ")
          print(prop)

        case UnaryOp(op, lhs) =>
          import ir.Trees.JSUnaryOp._
          print('(')
          if (op == `typeof`) {
            print("typeof ")
          } else {
            (op: @switch) match {
              case + => print('+')
              case - => print('-')
              case ~ => print('~')
              case ! => print('!')
              case `typeof` => print("typeof ")
            }
          }
          print(lhs)
          print(')')

        case IncDec(prefix, inc, arg) =>
          val op = if (inc) "++" else "--"
          print('(')
          if (prefix)
            print(op)
          print(arg)
          if (!prefix)
            print(op)
          print(')')

        case BinaryOp(op, lhs, rhs) =>
          import ir.Trees.JSBinaryOp._
          print('(')
          print(lhs)
          print(' ')
          print((op: @switch) match {
            case === => "==="
            case !== => "!=="

            case + => "+"
            case - => "-"
            case * => "*"
            case / => "/"
            case % => "%"

            case |   => "|"
            case &   => "&"
            case ^   => "^"
            case <<  => "<<"
            case >>  => ">>"
            case >>> => ">>>"

            case <  => "<"
            case <= => "<="
            case >  => ">"
            case >= => ">="

            case && => "&&"
            case || => "||"

            case `in`         => "in"
            case `instanceof` => "instanceof"
          })
          print(' ')
          print(rhs)
          print(')')

        case ArrayConstr(items) =>
          printRow(items, '[', ']')

        case ObjectConstr(Nil) =>
          if (isStat)
            print("({})") // force expression position for the object literal
          else
            print("{}")

        case ObjectConstr(fields) =>
          if (isStat)
            print('(') // force expression position for the object literal
          print('{')
          indent()
          println()
          var rest = fields
          while (rest.nonEmpty) {
            val x = rest.head
            rest = rest.tail
            print(x._1)
            print(": ")
            print(x._2)
            if (rest.nonEmpty) {
              print(',')
              println()
            }
          }
          undent()
          println()
          print('}')
          if (isStat)
            print(')')

        // Literals

        case Undefined() =>
          print("(void 0)")

        case Null() =>
          print("null")

        case BooleanLiteral(value) =>
          print(if (value) "true" else "false")

        case IntLiteral(value) =>
          if (value >= 0) {
            print(value.toString)
          } else {
            print('(')
            print(value.toString)
            print(')')
          }

        case DoubleLiteral(value) =>
          if (value == 0 && 1 / value < 0) {
            print("(-0)")
          } else if (value >= 0) {
            print(value.toString)
          } else {
            print('(')
            print(value.toString)
            print(')')
          }

        case StringLiteral(value) =>
          print('\"')
          printEscapeJS(value)
          print('\"')

        case BigIntLiteral(value) =>
          if (value >= 0) {
            print(value.toString)
            print('n')
          } else {
            print('(')
            print(value.toString)
            print("n)")
          }

        // Atomic expressions

        case VarRef(ident) =>
          print(ident)

        case This() =>
          print("this")

        case Function(arrow, args, restParam, body) =>
          if (arrow) {
            print('(')
            printSig(args, restParam)
            print("=> ")
            body match {
              case Return(expr: ObjectConstr) =>
                /* #3926 An ObjectConstr needs to be wrapped in () not to be
                 * parsed as a block.
                 */
                print('(')
                print(expr)
                print(')')
              case Return(expr) =>
                print(expr)
              case _ =>
                printBlock(body)
            }
            print(')')
          } else {
            print("(function")
            printSig(args, restParam)
            printBlock(body)
            print(')')
          }

        // Named function definition

        case FunctionDef(name, args, restParam, body) =>
          if (!isStat)
            print('(')
          print("function ")
          print(name)
          printSig(args, restParam)
          printBlock(body)
          if (!isStat)
            print(')')

        // ECMAScript 6 classes

        case ClassDef(optClassName, optParentClass, members) =>
          print("class")
          if (optClassName.isDefined) {
            print(' ')
            print(optClassName.get)
          }
          if (optParentClass.isDefined) {
            print(" extends ")
            print(optParentClass.get)
          }
          print(" {"); indent()
          var rest = members
          while (rest.nonEmpty) {
            println()
            print(rest.head)
            print(';')
            rest = rest.tail
          }
          undent(); println(); print('}')

        case MethodDef(static, name, params, restParam, body) =>
          if (static)
            print("static ")
          print(name)
          printSig(params, restParam)
          printBlock(body)

        case GetterDef(static, name, body) =>
          if (static)
            print("static ")
          print("get ")
          print(name)
          print("() ")
          printBlock(body)

        case SetterDef(static, name, param, body) =>
          if (static)
            print("static ")
          print("set ")
          print(name)
          print('(')
          print(param)
          print(") ")
          printBlock(body)

        case Super() =>
          print("super")

        // ECMAScript 6 modules

        case Import(bindings, from) =>
          print("import { ")
          var first = true
          var rest = bindings
          while (rest.nonEmpty) {
            val binding = rest.head
            if (first)
              first = false
            else
              print(", ")
            print(binding._1)
            print(" as ")
            print(binding._2)
            rest = rest.tail
          }
          print(" } from ")
          print(from: Tree)

        case ImportNamespace(binding, from) =>
          print("import * as ")
          print(binding)
          print(" from ")
          print(from: Tree)

        case Export(bindings) =>
          print("export { ")
          var first = true
          var rest = bindings
          while (rest.nonEmpty) {
            val binding = rest.head
            if (first)
              first = false
            else
              print(", ")
            print(binding._1)
            print(" as ")
            print(binding._2)
            rest = rest.tail
          }
          print(" }")

        case ExportImport(bindings, from) =>
          print("export { ")
          var first = true
          var rest = bindings
          while (rest.nonEmpty) {
            val binding = rest.head
            if (first)
              first = false
            else
              print(", ")
            print(binding._1)
            print(" as ")
            print(binding._2)
            rest = rest.tail
          }
          print(" } from ")
          print(from: Tree)

        case _ =>
          throw new IllegalArgumentException(
              s"Unexpected tree of class ${tree.getClass.getName} at ${tree.pos}")
      }
    }

    protected def printEscapeJS(s: String): Unit =
      Utils.printEscapeJS(s, out)

    protected def print(ident: Ident): Unit =
      printEscapeJS(ident.name)

    private final def print(propName: PropertyName): Unit = propName match {
      case lit: StringLiteral => print(lit: Tree)
      case ident: Ident       => print(ident)

      case ComputedName(tree) =>
        print("[")
        print(tree)
        print("]")
    }

    protected def print(exportName: ExportName): Unit =
      printEscapeJS(exportName.name)

    protected def print(s: String): Unit =
      out.write(s)

    protected def print(c: Int): Unit =
      out.write(c)
  }

  class JSTreePrinterWithSourceMap(_out: Writer,
      sourceMap: SourceMapWriter) extends JSTreePrinter(_out) {

    private var column = 0

    override def printTree(tree: Tree, isStat: Boolean): Unit = {
      val pos = tree.pos
      if (pos.isDefined)
        sourceMap.startNode(column, pos)

      super.printTree(tree, isStat)

      if (pos.isDefined)
        sourceMap.endNode(column)
    }

    override protected def printEscapeJS(s: String): Unit =
      column += Utils.printEscapeJS(s, out)

    override protected def print(ident: Ident): Unit = {
      if (ident.pos.isDefined)
        sourceMap.startIdentNode(column, ident.pos, ident.originalName)
      super.print(ident)
      if (ident.pos.isDefined)
        sourceMap.endNode(column)
    }

    override protected def println(): Unit = {
      super.println()
      sourceMap.nextLine()
      column = this.getIndentMargin()
    }

    override protected def print(s: String): Unit = {
      // assume no EOL char in s, and assume s only has ASCII characters
      super.print(s)
      column += s.length()
    }

    override protected def print(c: Int): Unit = {
      // assume c is not EOL, and assume c is an ASCII character
      super.print(c)
      column += 1
    }
  }

}
