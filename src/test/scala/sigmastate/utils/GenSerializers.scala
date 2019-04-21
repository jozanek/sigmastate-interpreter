package sigmastate.utils

import scalan.util.FileUtil
import sigmastate.serialization.ValueSerializer._
import sigma.util.Extensions._

/** Generate contents of ErgoTree serializer format specification.
  */
object GenSerializers extends SpecGen {

  def printDataScope(dataScope: DataScope, level: Int, sb: StringBuilder) = {
    val prefix = "~~" * level
    val name = dataScope.name
    val fmt = dataScope.data.format
    val size = fmt.size
    val desc = dataScope.data.info.description
    val row =
      s"""    $prefix $name & \\lst{$fmt} & \\text{$size} & \\text{$desc} \\\\
         |    \\hline
      """.stripMargin
    sb.append(row)
    openRow = false
  }

  def printForScope(scope: ForScope, level: Int, sb: StringBuilder) = {
    val prefix = "~~" * level
    val header =
      s"""    \\multicolumn{4}{l}{${prefix}\\lst{for}~i=1~\\lst{to}~${scope.limitVar}} \\\\
        |    \\hline
         """.stripMargin
    sb.append(header)

    for ((_, s) <- scope.children) {
      printScope(s, level + 1, sb)
    }

    val footer = s"    \\multicolumn{4}{l}{${prefix}\\lst{end for}} \\\\"
    sb.append(footer)
    openRow = true
  }

  def printOptionScope(scope: OptionScope, level: Int, sb: StringBuilder) = {
    val prefix = "~~" * level
    val header =
      s"""    \\multicolumn{4}{l}{${prefix}\\lst{optional}~${scope.name}} \\\\
         |    \\hline
         |    ${prefix}~~tag & \\lst{Byte} & 1 & \\text{0 - no value; 1 - has value} \\\\
         |    \\hline
         |    \\multicolumn{4}{l}{${prefix}~~\\lst{when}~tag == 1} \\\\
         |    \\hline
         """.stripMargin
    sb.append(header)

    for ((_, s) <- scope.children) {
      printScope(s, level + 2, sb)
    }

    val footer = s"    \\multicolumn{4}{l}{${prefix}\\lst{end optional}} \\\\"
    sb.append(footer)
    openRow = true
  }

  def printCasesScope(scope: CasesScope, level: Int, sb: StringBuilder) = {
    val prefix = "~~" * level
    val header =
      s"""    \\multicolumn{4}{l}{${prefix}\\lst{match}~${scope.matchExpr}} \\\\
         """.stripMargin
    sb.append(header)

    for (when <- scope.cases) {
      val pattern =
        s"""
         |    \\multicolumn{4}{l}{${prefix}~~${ if(when.isOtherwise) s"\\lst{$otherwiseCondition}" else s"\\lst{with}~${when.condition}" } } \\\\
         |    \\hline
        """.stripMargin
      sb.append(pattern)
      for((_,s) <- when.children) {
        printScope(s, level + 2, sb)
      }
    }

    val footer = s"    \\multicolumn{4}{l}{${prefix}\\lst{end match}} \\\\"
    sb.append(footer)
    openRow = true
  }

  def printScope(scope: Scope, level: Int, sb: StringBuilder): Unit = {
    if (openRow) {
      sb.append(s"\\hline\n")
      openRow = false // close the table row with a horizontal line
    }
    scope match {
      case scope: DataScope =>
        printDataScope(scope, level, sb)
      case scope: ForScope =>
        printForScope(scope, level, sb)
      case scope: OptionScope =>
        printOptionScope(scope, level, sb)
      case scope: CasesScope =>
        printCasesScope(scope, level, sb)
      case _ =>
        sb.append(s"% skipped $scope\n")
    }
  }

  var openRow: Boolean = false

  def printSerScopeSlots(serScope: SerScope) = {
    val rows = StringBuilder.newBuilder
    openRow = false
    serScope.children.map { case (name, scope) =>
       printScope(scope, 0, rows)
    }
    rows.result()
  }

  def printSerializerSections() = {
    val scopes = serializerInfo
      .filter(_._2.children.nonEmpty).toSeq
      .sortBy(_._1).map(_._2)
    scopes.map { s =>
      val ser = getSerializer(s.opCode)
      val opCode = ser.opCode.toUByte
      val opName = ser.opDesc.typeName
      val rows = printSerScopeSlots(s)
      s"""
        |\\subsubsection{\\lst{$opName} operation (OpCode $opCode)}
        |
        |\\noindent
        |\\(\\begin{array}{| l | l | l | l |}
        |    \\hline
        |    \\bf{Slot} & \\bf{Format} & \\bf{\\#bytes} & \\bf{Description} \\\\
        |    \\hline
        |    $rows
        |\\end{array}\\)
       """.stripMargin
    }.mkString("\n")
  }

  def generateSerSpec() = {
    val fileName = "ergotree_serialization1.tex"
    val formatsTex = printSerializerSections()
    val file = FileUtil.file(s"docs/spec/generated/$fileName")
    FileUtil.write(file, formatsTex)

    println(s"\\input{generated/$fileName}")
  }

}