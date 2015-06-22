package org.jetbrains.plugins.scala.meta.trees

import org.jetbrains.plugins.scala.lang.psi.api.base._
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.{ScalaPsiElement, api => p, types => ptype}

import scala.collection.immutable.Seq
import scala.language.postfixOps
import scala.meta.internal.ast.Term.Param
import scala.meta.internal.{ast => m}
import scala.{Seq => _}

trait TreeAdapter {
  self: Converter =>

  def ideaToMeta(tree: ScalaPsiElement): m.Tree = {
    tree match {
      case t: ScValueDeclaration =>
        m.Decl.Val(convertMods(t), Seq(t.getIdList.fieldIds map { it => m.Pat.Var.Term(toTermName(it))}:_*), toType(t.typeElement.get.calcType))
      case t: ScVariableDeclaration =>
        m.Decl.Var(convertMods(t), Seq(t.getIdList.fieldIds map { it => m.Pat.Var.Term(toTermName(it))}:_*), toType(t.typeElement.get.calcType))
      case t: ScTypeAliasDeclaration =>
        m.Decl.Type(convertMods(t), toTypeName(t), Seq(t.typeParameters map toType: _*), typeBounds(t))
      case t: ScTypeAliasDefinition =>
        m.Defn.Type(convertMods(t), toTypeName(t), Seq(t.typeParameters map toType:_*), toType(t.aliasedType))
      case t: ScFunctionDeclaration =>
        m.Decl.Def(convertMods(t), toTermName(t), Seq(t.typeParameters map toType:_*), Seq(t.paramClauses.clauses.map(convertParamClause):_*), returnType(t.returnType))
      case t: ScPatternDefinition =>
        patternDefinition(t)
      case t: ScVariableDefinition =>
        def pattern(bp: patterns.ScBindingPattern) = m.Pat.Var.Term(toTermName(bp))
        m.Defn.Var(convertMods(t), Seq(t.bindings.map(pattern):_*), t.declaredType.map(toType), expression(t.expr))
      case t: ScFunctionDefinition =>
        m.Defn.Def(convertMods(t), toTermName(t),
          Seq(t.typeParameters map toType:_*),
          Seq(t.paramClauses.clauses.map(convertParamClause):_*),
          t.definedReturnType.map(toType).toOption,
          expression(t.body).get
        )
      case t: ScMacroDefinition =>
        m.Defn.Macro(
          convertMods(t), toTermName(t),
          Seq(t.typeParameters map toType:_*),
          Seq(t.paramClauses.clauses.map(convertParamClause):_*),
          t.definedReturnType.map(toType).get,
          expression(t.body).get
        )
      case t: ScTrait => toTrait(t)
      case t: ScClass => toClass(t)
      case t: ScObject => toObject(t)
      case t: ScExpression => expression(Some(t)).get
      case t: p.toplevel.imports.ScImportStmt => m.Import(Seq(t.importExprs.map(imports):_*))
      case other => other ?!
    }
  }

  def toTrait(t: ScTrait) = m.Defn.Trait(
    convertMods(t),
    toTypeName(t),
    Seq(t.typeParameters map toType:_*),
    m.Ctor.Primary(Nil, m.Ctor.Ref.Name("this"), Nil),
    template(t.extendsBlock)
  )

  def toClass(c: ScClass) = m.Defn.Class(
    convertMods(c),
    toTypeName(c),
    Seq(c.typeParameters map toType:_*),
    ctor(c.constructor),
    template(c.extendsBlock)
  )

  def toObject(o: ScObject) = m.Defn.Object(
    convertMods(o),
    toTermName(o),
    m.Ctor.Primary(Nil, m.Ctor.Ref.Name("this"), Nil),
    template(o.extendsBlock)
  )

  def ctor(pc: Option[ScPrimaryConstructor]): m.Ctor.Primary = {
    pc match {
      case None => throw new RuntimeException("no primary constructor in class")
      case Some(ctor) => m.Ctor.Primary(convertMods(ctor), toPrimaryCtorName(ctor), Seq(ctor.parameterList.clauses.map(convertParamClause):_*))
    }
  }

  def caseClause(c: patterns.ScCaseClause): m.Case = {
    m.Case(pattern(c.pattern.get), c.guard.map(it => expression(it.expr.get)), expression(c.expr.get).asInstanceOf[m.Term.Block])
  }

  def pattern(pt: patterns.ScPattern): m.Pat = {
    import p.base.patterns._

    import m.Pat._
    def compose(lst: Seq[ScPattern]): m.Pat = lst match {
      case x :: Nil => pattern(x)
      case x :: xs  => Alternative(pattern(x), compose(xs))
    }
    // WHY??(((
    def arg(pt: patterns.ScPattern): m.Pat.Arg = pt match {
      case t: ScSeqWildcard       =>  Arg.SeqWildcard()
      case t: ScWildcardPattern   =>  Wildcard()
      case t: ScStableReferenceElementPattern => toTermName(t.refElement.get.resolve())
      case t: ScPattern           => pattern(t)
    }
    pt match {
      case t: ScReferencePattern  =>  Var.Term(toTermName(t))
      case t: ScConstructorPattern=>  Extract(toTermName(t.ref), Nil, Seq(t.args.patterns.map(arg):_*))
      case t: ScNamingPattern     =>  Bind(Var.Term(toTermName(t)), arg(t.named))
      case t@ ScTypedPattern(te: types.ScWildcardTypeElement) => Typed(if (t.isWildcard) Wildcard() else Var.Term(toTermName(t)), Type.Wildcard())
      case t@ ScTypedPattern(te)  =>  Typed(if (t.isWildcard) Wildcard() else Var.Term(toTermName(t)), toType(te).patTpe)
      case t: ScLiteralPattern    =>  literal(t.getLiteral)
      case t: ScTuplePattern      =>  Tuple(Seq(t.patternList.get.patterns.map(pattern):_*))
      case t: ScWildcardPattern   =>  Wildcard()
      case t: ScCompositePattern  =>  compose(Seq(t.subpatterns : _*))
      case t: ScInfixPattern      =>  ExtractInfix(pattern(t.leftPattern), toTermName(t.refernece), t.rightPattern.map(pt=>Seq(pattern(pt))).getOrElse(Nil))
      case t: ScPattern => t ?!
    }
  }

  def template(t: p.toplevel.templates.ScExtendsBlock): m.Template = {
    def ctor(tpe: types.ScTypeElement) = m.Ctor.Ref.Name(tpe.calcType.canonicalText)
    val exprs   = t.templateBody map (it => Seq(it.exprs.map(expression): _*))
    val holders = t.templateBody map (it => Seq(it.holders.map(ideaToMeta(_).asInstanceOf[m.Stat]): _*))
    val early   = t.earlyDefinitions map (it => Seq(it.members.map(ideaToMeta(_).asInstanceOf[m.Stat]):_*)) getOrElse Seq.empty
    val parents = t.templateParents map (it => Seq(it.typeElements map ctor :_*)) getOrElse Seq.empty
    val self    = t.selfType match {
      case Some(tpe: ptype.ScType) => m.Term.Param(Nil, m.Term.Name("self"), Some(toType(tpe)), None)
      case None => m.Term.Param(Nil, m.Name.Anonymous(), None, None)
    }
    val stats = (exprs, holders) match {
      case (Some(exp), Some(hld)) => Some(hld ++ exp)
      case (Some(exp), None)  => Some(exp)
      case (None, Some(hld))  => Some(hld)
      case (None, None)       => None
    }
    m.Template(early, parents, self, stats)
  }

  def template(t: ScTemplateDefinition): m.Template = {
    val early   = t.extendsBlock.earlyDefinitions map (it => Seq(it.members.map(ideaToMeta(_).asInstanceOf[m.Stat]):_*)) getOrElse Seq.empty
    val ctor = t.extendsBlock.templateParents match {
      case Some(parents: p.toplevel.templates.ScClassParents) => toCtor(parents.constructor.get)
    }
    val self    = t.selfType match {
      case Some(tpe: ptype.ScType) => m.Term.Param(Nil, m.Term.Name("self"), Some(toType(tpe)), None)
      case None => m.Term.Param(Nil, m.Name.Anonymous(), None, None)
    }
    m.Template(early, Seq(ctor), self, None)
  }

  def toCtor(c: ScConstructor) = {
    if (c.arguments.isEmpty)
      toTermName(c)
    else
      m.Term.Apply(toTermName(c), Seq(c.args.get.exprs.map(callArgs):_*))
  }

  def expression(e: ScExpression): m.Term = {
    import p.expr._
    e match {
      case t: ScLiteral => literal(t)
      case t: ScUnitExpr => m.Lit.Unit()
      case t: ScReturnStmt => m.Term.Return(expression(t.expr).get)
      case t: ScBlock => m.Term.Block(Seq(t.statements.map(ideaToMeta(_).asInstanceOf[m.Stat]):_*))
      case t: ScMethodCall => m.Term.Apply(toTermName(t.getInvokedExpr), Seq(t.args.exprs.map(callArgs):_*))
      case t: ScInfixExpr => m.Term.ApplyInfix(expression(t.getBaseExpr), toTermName(t.getInvokedExpr), Nil, Seq(expression(t.getArgExpr)))
      case t: ScPrefixExpr => m.Term.ApplyUnary(toTermName(t.operation), expression(t.operand))
      case t: ScIfStmt => m.Term.If(expression(t.condition.get),
        t.thenBranch.map(expression).getOrElse(m.Lit.Unit()), t.elseBranch.map(expression).getOrElse(m.Lit.Unit()))
      case t: ScMatchStmt => m.Term.Match(expression(t.expr.get), Seq(t.caseClauses.map(caseClause):_*))
      case t: ScReferenceExpression => toTermName(t)
      case t: ScNewTemplateDefinition => m.Term.New(template(t))
      case t: ScFunctionExpr => m.Term.Function(Seq(t.parameters.map(convertParam):_*), expression(t.result).get)
      case other: ScalaPsiElement => other ?!
    }
  }

  def callArgs(e: ScExpression) = {
    e match {
      case t: ScAssignStmt => m.Term.Arg.Named(toTermName(t.getLExpression), expression(t.getRExpression).get)
      case t: ScUnderscoreSection => m.Term.Placeholder()
      case other => expression(e)
    }
  }

  def expression(tree: Option[ScExpression]): Option[m.Term] = {
    tree match {
      case Some(_: ScUnderscoreSection) => None
      case Some(expr) => Some(expression(expr))
      case None => None
    }
  }

  def imports(t: p.toplevel.imports.ScImportExpr):m.Import.Clause = {
    def qual(q: ScStableCodeReferenceElement): m.Term.Ref = {
      q.pathQualifier match {
        case Some(parent: ScSuperReference) =>
          m.Term.Select(m.Term.Super(m.Name.Anonymous(), m.Name.Anonymous()), toTermName(q))
        case Some(parent: ScThisReference) =>
          m.Term.Select(m.Term.This(m.Name.Anonymous()), toTermName(q))
        case Some(parent:ScStableCodeReferenceElement) =>
          m.Term.Select(qual(parent), toTermName(q))
        case Some(other) => other ?!
        case None         => toTermName(q)
      }
    }
    def selector(sel: p.toplevel.imports.ScImportSelector): m.Import.Selector = {
      if (sel.isAliasedImport && sel.importedName == "_")
        m.Import.Selector.Unimport(ind(sel.reference))
      else if (sel.isAliasedImport)
        m.Import.Selector.Rename(m.Name.Indeterminate(sel.reference.qualName), m.Name.Indeterminate(sel.importedName))
      else
        m.Import.Selector.Name(m.Name.Indeterminate(sel.importedName))
    }
    if (t.selectors.nonEmpty)
      m.Import.Clause(qual(t.qualifier), Seq(t.selectors.map(selector):_*) ++ (if (t.singleWildcard) Seq(m.Import.Selector.Wildcard()) else Seq.empty))
    else if (t.singleWildcard)
      m.Import.Clause(qual(t.qualifier), Seq(m.Import.Selector.Wildcard()))
    else
      m.Import.Clause(qual(t.qualifier), Seq(m.Import.Selector.Name(m.Name.Indeterminate(t.getNames.head))))
  }

  def literal(l: ScLiteral): m.Lit = {
    import p.base.ScLiteral

    import m.Lit
    l match {
      case ScLiteral(i: Integer)              => Lit.Int(i)
      case ScLiteral(l: java.lang.Long)       => Lit.Long(l)
      case ScLiteral(f: java.lang.Float)      => Lit.Float(f)
      case ScLiteral(d: java.lang.Double)     => Lit.Double(d)
      case ScLiteral(b: java.lang.Boolean)    => Lit.Bool(b)
      case ScLiteral(c: java.lang.Character)  => Lit.Char(c)
      case ScLiteral(b: java.lang.Byte)       => Lit.Byte(b)
      case ScLiteral(s: String)               => Lit.String(s)
      case ScLiteral(null)                    => Lit.Null()
      case _ if l.isSymbol                    => Lit.Symbol(l.getValue.asInstanceOf[Symbol])
      case other => other ?!
    }
  }

  def patternDefinition(t: ScPatternDefinition): m.Tree = {
    def pattern(bp: patterns.ScBindingPattern): m.Pat = {
      m.Pat.Var.Term(toTermName(bp))
    }

    if(t.bindings.exists(_.isVal))
      m.Defn.Val(convertMods(t), Seq(t.bindings.map(pattern):_*), t.declaredType.map(toType), expression(t.expr).get)
    else if(t.bindings.exists(_.isVar))
      m.Defn.Var(convertMods(t), Seq(t.bindings.map(pattern):_*), t.declaredType.map(toType), expression(t.expr))
    else ???
  }

  def convertMods(t: p.toplevel.ScModifierListOwner): Seq[m.Mod] = {
    import p.base.ScAccessModifier.Type._
    def extractClassParameter(param: params.ScClassParameter): Seq[m.Mod] = {
      if      (param.isVar) Seq(m.Mod.VarParam())
      else if (param.isVal) Seq(m.Mod.ValParam())
      else Seq.empty
    }
    if (t.getModifierList == null) return Nil // workaround for 9ec0b8a44
    val name = t.getModifierList.accessModifier match {
      case Some(mod) => mod.idText match {
        case Some(qual) => m.Name.Indeterminate(qual)
        case None       => m.Name.Anonymous()
      }
      case None => m.Name.Anonymous()
    }
    val classParam = t match {
      case param: params.ScClassParameter => extractClassParameter(param)
      case _ => Seq.empty
    }
    val common = t.getModifierList.accessModifier match {
      case Some(mod) if mod.access == PRIVATE        => Seq(m.Mod.Private(name))
      case Some(mod) if mod.access == PROTECTED      => Seq(m.Mod.Protected(name))
      case Some(mod) if mod.access == THIS_PRIVATE   => Seq(m.Mod.Private(m.Term.This(name)))
      case Some(mod) if mod.access == THIS_PROTECTED => Seq(m.Mod.Protected(m.Term.This(name)))
      case None => Seq.empty
    }
    val overrideMod = if (t.hasModifierProperty("override")) Seq(m.Mod.Override()) else Nil
    overrideMod ++ common ++ classParam
  }

  def convertParamClause(paramss: params.ScParameterClause): Seq[Param] = {
    Seq(paramss.parameters.map(convertParam):_*)
  }

  protected def convertParam(param: params.ScParameter): m.Term.Param = {
      val mods = convertMods(param) ++ (if (param.isImplicitParameter) Seq(m.Mod.Implicit()) else Seq.empty)
      if (param.isVarArgs)
        m.Term.Param(mods, toTermName(param), param.typeElement.map(tp => m.Type.Arg.Repeated(toType(tp))), None)
      else
        m.Term.Param(mods, toTermName(param), param.typeElement.map(toType), None)
  }
}
