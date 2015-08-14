package org.jetbrains.plugins.scala.lang.refactoring.util

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi._
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaBundle
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScForStatement, ScWhileStmt, ScIfStmt, ScBlockExpr}
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScTypeParam
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScExtendsBlock, ScTemplateBody}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScClass}
import org.jetbrains.plugins.scala.lang.resolve.ResolveTargets
import org.jetbrains.plugins.scala.lang.resolve.ResolveTargets._
import org.jetbrains.plugins.scala.lang.resolve.processor.BaseProcessor

import scala.collection.mutable.ArrayBuffer

/**
 * Created by Kate Ustyuzhanina on 8/3/15.
 */
object ScalaTypeValidator {
  def apply(conflictsReporter: ConflictsReporter,
            project: Project,
            editor: Editor,
            file: PsiFile,
            element: PsiElement,
            occurrences: Array[ScTypeElement]): ScalaTypeValidator = {
    val container = ScalaRefactoringUtil.enclosingContainer(PsiTreeUtil.findCommonParent(occurrences: _*))
    val containerOne = ScalaRefactoringUtil.enclosingContainer(element)
    new ScalaTypeValidator(conflictsReporter, project, element, occurrences.isEmpty, container, containerOne)
  }

  def apply(conflictsReporter: ConflictsReporter,
            project: Project,
            editor: Editor,
            file: PsiFile,
            element: PsiElement,
            occurrences: Array[TextRange]): ScalaTypeValidator = {
    val container = ScalaRefactoringUtil.enclosingContainer(ScalaRefactoringUtil.commonParent(file, occurrences: _*))
    val containerOne = ScalaRefactoringUtil.enclosingContainer(element)
    new ScalaTypeValidator(conflictsReporter, project, element, occurrences.isEmpty, container, containerOne)
  }
}


class ScalaTypeValidator(conflictsReporter: ConflictsReporter,
                         myProject: Project,
                         selectedElement: PsiElement,
                         noOccurrences: Boolean,
                         enclosingContainerAll: PsiElement,
                         enclosingOne: PsiElement)
  extends ScalaValidator(conflictsReporter, myProject, selectedElement, noOccurrences, enclosingContainerAll, enclosingOne) {

  override def findConflicts(name: String, allOcc: Boolean): Array[(PsiNamedElement, String)] = {
    //returns declaration and message
    val container = enclosingContainer(allOcc)
    if (container == null) return Array()
    val buf = new ArrayBuffer[(PsiNamedElement, String)]
    buf ++= getForbiddenNames(container, name)
    if (container.isInstanceOf[ScalaFile]) {
      buf ++= getForbiddenNamesHelper(container, name)
    }
    buf.toArray
  }

  //TODO maybe not the best way to handle with such matching
  private def matchElement(element: PsiElement, name: String, buf: ArrayBuffer[(PsiNamedElement, String)]): Boolean = {
    element match {
      case typeAlias: ScTypeAliasDefinition if typeAlias.getName == name =>
        buf += ((typeAlias, messageForTypeAliasMember(name)))
        true
      case typeDecl: ScTypeAliasDeclaration if typeDecl.getName == name =>
        buf += ((typeDecl, messageForTypeAliasMember(name)))
        true
      case typeParametr: ScTypeParam if typeParametr.getName == name =>
        buf += ((typeParametr, messageForTypeAliasMember(name)))
        true
      case clazz: ScClass =>
        if (clazz.getName == name) {
          buf += ((clazz, messageForClassMember(name)))
        }
        buf ++= getForbiddenNamesHelper(clazz, name)
        true
      case objectType: ScObject =>
        if (objectType.getName == name) {
          buf += ((objectType, messageForClassMember(name)))
        }
        buf ++= getForbiddenNamesHelper(objectType, name)
        true
      case fileType: ScalaFile =>
        buf ++= getForbiddenNamesHelper(fileType, name)
        true
      case func: ScFunctionDefinition =>
        buf ++= getForbiddenNamesHelper(func, name)
        true
      case funcBlock: ScBlockExpr =>
        buf ++= getForbiddenNamesHelper(funcBlock, name)
        true
      case extendsBlock: ScExtendsBlock =>
        buf ++= getForbiddenNamesHelper(extendsBlock, name)
        true
      case body: ScTemplateBody =>
        buf ++= getForbiddenNamesHelper(body, name)
        true
      case ifStatement: ScIfStmt =>
        buf ++= getForbiddenNamesHelper(ifStatement, name)
        true
      case whileStatement: ScWhileStmt =>
        buf ++= getForbiddenNamesHelper(whileStatement, name)
        true
      case forStatement: ScForStatement =>
        buf ++= getForbiddenNamesHelper(forStatement, name)
        true
      case _ => true
    }
  }

  //traverse by typealias, classname, object, functions name that available in this point
  private def getForbiddenNames(position: PsiElement, name: String) = {
    class FindTypeAliasProcessor extends BaseProcessor(ValueSet(ResolveTargets.CLASS, ResolveTargets.METHOD)) {
      val buf = new ArrayBuffer[(PsiNamedElement, String)]

      override def execute(element: PsiElement, state: ResolveState): Boolean = {
        matchElement(element, name, buf)
      }
    }

    val processor = new FindTypeAliasProcessor
    PsiTreeUtil.treeWalkUp(processor, position, null, ResolveState.initial())
    processor.buf
  }

  //traverse
  private def getForbiddenNamesHelper(commonParent: PsiElement, name: String): ArrayBuffer[(PsiNamedElement, String)] = {
    val buf = new ArrayBuffer[(PsiNamedElement, String)]
    for (child <- commonParent.getChildren) {
      matchElement(child, name, buf)
    }
    buf
  }

  override def validateName(name: String, increaseNumber: Boolean): String = {
    val newName = name.toUpperCase
    if (noOccurrences) return newName
    var res = newName
    if (isOKImpl(res, allOcc = false).isEmpty) return res
    if (!increaseNumber) return ""
    var i = 1
    res = newName + i
    while (!isOKImpl(res, allOcc = true).isEmpty) {
      i = i + 1
      res = newName + i
    }
    res
  }

  private def messageForTypeAliasMember(name: String) = ScalaBundle.message("introduced.typealias.will.conflict.with.type.name", name)

  private def messageForClassMember(name: String) = ScalaBundle.message("introduced.typealias.will.conflict.with.class.name", name)
}

