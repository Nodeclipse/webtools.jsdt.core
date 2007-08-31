/*******************************************************************************
 * Copyright (c) 2000, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.wst.jsdt.internal.compiler.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;

import org.eclipse.wst.jsdt.core.compiler.CategorizedProblem;
import org.eclipse.wst.jsdt.core.compiler.CharOperation;
import org.eclipse.wst.jsdt.internal.compiler.ASTVisitor;
import org.eclipse.wst.jsdt.internal.compiler.ClassFile;
import org.eclipse.wst.jsdt.internal.compiler.CompilationResult;
import org.eclipse.wst.jsdt.internal.compiler.flow.FlowContext;
import org.eclipse.wst.jsdt.internal.compiler.flow.FlowInfo;
import org.eclipse.wst.jsdt.internal.compiler.flow.InitializationFlowContext;
import org.eclipse.wst.jsdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.wst.jsdt.internal.compiler.lookup.BlockScope;
import org.eclipse.wst.jsdt.internal.compiler.lookup.CompilationUnitBinding;
import org.eclipse.wst.jsdt.internal.compiler.lookup.CompilationUnitScope;
import org.eclipse.wst.jsdt.internal.compiler.lookup.ImportBinding;
import org.eclipse.wst.jsdt.internal.compiler.lookup.LocalTypeBinding;
import org.eclipse.wst.jsdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.wst.jsdt.internal.compiler.lookup.MethodScope;
import org.eclipse.wst.jsdt.internal.compiler.lookup.PackageBinding;
import org.eclipse.wst.jsdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.wst.jsdt.internal.compiler.lookup.TypeConstants;
import org.eclipse.wst.jsdt.internal.compiler.parser.NLSTag;
import org.eclipse.wst.jsdt.internal.compiler.problem.AbortCompilationUnit;
import org.eclipse.wst.jsdt.internal.compiler.problem.AbortMethod;
import org.eclipse.wst.jsdt.internal.compiler.problem.AbortType;
import org.eclipse.wst.jsdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.wst.jsdt.internal.compiler.problem.ProblemSeverities;
import org.eclipse.wst.jsdt.internal.compiler.util.HashtableOfObject;
import org.eclipse.wst.jsdt.internal.infer.InferredAttribute;
import org.eclipse.wst.jsdt.internal.infer.InferredMethod;
import org.eclipse.wst.jsdt.internal.infer.InferredType;

public class CompilationUnitDeclaration
	extends ASTNode
	implements ProblemSeverities, ReferenceContext {
	
	private static final Comparator STRING_LITERAL_COMPARATOR = new Comparator() {
		public int compare(Object o1, Object o2) {
			StringLiteral literal1 = (StringLiteral) o1;
			StringLiteral literal2 = (StringLiteral) o2;
			return literal1.sourceStart - literal2.sourceStart;
		}
	};
	private static final int STRING_LITERALS_INCREMENT = 10;

	public ImportReference currentPackage;
	public ImportReference[] imports;
	public TypeDeclaration[] types;
	public ProgramElement[] statements;
	public int[][] comments;


	public InferredType [] inferredTypes = new InferredType[10];
	public int numberInferredTypes=0;
	public HashtableOfObject inferredTypesHash=new HashtableOfObject();
	
	public boolean ignoreFurtherInvestigation = false;	// once pointless to investigate due to errors
	public boolean ignoreMethodBodies = false;
	public CompilationUnitScope scope;
	public ProblemReporter problemReporter;
	public CompilationResult compilationResult;

	
	public LocalTypeBinding[] localTypes;
	public int localTypeCount = 0;

	public CompilationUnitBinding compilationUnitBinding;
	
	
	public boolean isPropagatingInnerClassEmulation;

	public Javadoc javadoc; // 1.5 addition for package-info.js
	
	public NLSTag[] nlsTags;
	private StringLiteral[] stringLiterals;
	private int stringLiteralsPtr;
	

	
	public CompilationUnitDeclaration(
		ProblemReporter problemReporter,
		CompilationResult compilationResult,
		int sourceLength) {

		this.problemReporter = problemReporter;
		this.compilationResult = compilationResult;

		//by definition of a compilation unit....
		sourceStart = 0;
		sourceEnd = sourceLength - 1;
	}

	/*
	 *	We cause the compilation task to abort to a given extent.
	 */
	public void abort(int abortLevel, CategorizedProblem problem) {

		switch (abortLevel) {
			case AbortType :
				throw new AbortType(this.compilationResult, problem);
			case AbortMethod :
				throw new AbortMethod(this.compilationResult, problem);
			default :
				throw new AbortCompilationUnit(this.compilationResult, problem);
		}
	}

	/*
	 * Dispatch code analysis AND request saturation of inner emulation
	 */
	public void analyseCode() {

		if (ignoreFurtherInvestigation )
			return;
		try {
			if (types != null) {
				for (int i = 0, count = types.length; i < count; i++) {
					types[i].analyseCode(scope);
				}
			}
			// request inner emulation propagation
			propagateInnerEmulationForAllLocalTypes();
			
			this.scope.temporaryAnalysisIndex=0;
			int maxVars=this.scope.localIndex;
			for (Iterator iter = this.scope.externalCompilationUnits.iterator(); iter.hasNext();) {
				CompilationUnitScope externalScope = (CompilationUnitScope) iter.next();
				externalScope.temporaryAnalysisIndex=maxVars;
				maxVars+=externalScope.localIndex;
			}
			FlowInfo flowInfo=FlowInfo.initial(maxVars);
			FlowContext flowContext = new FlowContext(null, this);
	
			if (statements != null) {
				for (int i = 0, count = statements.length; i < count; i++) {
					flowInfo=((Statement)statements[i]).analyseCode(scope,flowContext,flowInfo);
				}
			}
			this.scope.reportUnusedDeclarations();
		} catch (AbortCompilationUnit e) {
			this.ignoreFurtherInvestigation = true;
			return;
		}
	}

	/*
	 * When unit result is about to be accepted, removed back pointers
	 * to compiler structures.
	 */
	public void cleanUp() {
		if (this.compilationUnitBinding!=null)
			this.compilationUnitBinding.scope=null;
		if (this.types != null) {
			for (int i = 0, max = this.types.length; i < max; i++) {
				cleanUp(this.types[i]);
			}
			for (int i = 0, max = this.localTypeCount; i < max; i++) {
			    LocalTypeBinding localType = localTypes[i];
				// null out the type's scope backpointers
				localType.scope = null; // local members are already in the list
				localType.enclosingCase = null;
			}
		}
		
		for (int i = 0; i < this.numberInferredTypes; i++) {
			SourceTypeBinding binding = this.inferredTypes[i].binding;
			if (binding!=null)
				binding.scope=null;
		}
		compilationResult.recoveryScannerData = null; // recovery is already done
		
		ClassFile[] classFiles = compilationResult.getClassFiles();
		for (int i = 0, max = classFiles.length; i < max; i++) {
			// clear the classFile back pointer to the bindings
			ClassFile classFile = classFiles[i];
			// null out the classfile backpointer to a type binding
			classFile.referenceBinding = null;
			classFile.innerClassesBindings = null;
		}
	}
	private void cleanUp(TypeDeclaration type) {
		if (type.memberTypes != null) {
			for (int i = 0, max = type.memberTypes.length; i < max; i++){
				cleanUp(type.memberTypes[i]);
			}
		}
		if (type.binding != null && type.binding.isAnnotationType())
			compilationResult.hasAnnotations = true;
		if (type.binding != null) {
			// null out the type's scope backpointers
			type.binding.scope = null;
		}
	}

	public void checkUnusedImports(){
		
		if (this.scope.imports != null){
			for (int i = 0, max = this.scope.imports.length; i < max; i++){
				ImportBinding importBinding = this.scope.imports[i];
				ImportReference importReference = importBinding.reference;
				if (importReference != null && ((importReference.bits & ASTNode.Used) == 0)){
					scope.problemReporter().unusedImport(importReference);
				}
			}
		}
	}
	
	public CompilationResult compilationResult() {
		return this.compilationResult;
	}

	/*
	 * Finds the matching type amoung this compilation unit types.
	 * Returns null if no type with this name is found.
	 * The type name is a compound name
	 * eg. if we're looking for X.A.B then a type name would be {X, A, B}
	 */
	public TypeDeclaration declarationOfType(char[][] typeName) {

		for (int i = 0; i < this.types.length; i++) {
			TypeDeclaration typeDecl = this.types[i].declarationOfType(typeName);
			if (typeDecl != null) {
				return typeDecl;
			}
		}
		return null;
	}
	
	
	public AbstractMethodDeclaration declarationOf(MethodBinding methodBinding) {
		if (methodBinding != null && this.statements != null) {
			for (int i = 0, max = this.statements.length; i < max; i++) {
				if (this.statements[i] instanceof AbstractMethodDeclaration)
				{
					AbstractMethodDeclaration methodDecl = (AbstractMethodDeclaration)this.statements[i];
					if (methodDecl.binding == methodBinding)
						return methodDecl;
				}
			}
		}
		return null;
	}


	/**
	 * Bytecode generation
	 */
	public void generateCode() {

		if (ignoreFurtherInvestigation) {
			if (types != null) {
				for (int i = 0, count = types.length; i < count; i++) {
					types[i].ignoreFurtherInvestigation = true;
					// propagate the flag to request problem type creation
					types[i].generateCode(scope);
				}
			}
			return;
		}
		if (this.isPackageInfo() && this.types != null && this.currentPackage.annotations != null) {
			types[0].annotations = this.currentPackage.annotations;
		}
		try {
			if (types != null) {
				for (int i = 0, count = types.length; i < count; i++)
					types[i].generateCode(scope);
			}
		} catch (AbortCompilationUnit e) {
			// ignore
		}
	}

	public char[] getFileName() {

		return compilationResult.getFileName();
	}

	public char[] getMainTypeName() {

		if (compilationResult.compilationUnit == null) {
			char[] fileName = compilationResult.getFileName();

			int start = CharOperation.lastIndexOf('/', fileName) + 1;
			if (start == 0 || start < CharOperation.lastIndexOf('\\', fileName))
				start = CharOperation.lastIndexOf('\\', fileName) + 1;

			int end = CharOperation.lastIndexOf('.', fileName);
			if (end == -1)
				end = fileName.length;

			return CharOperation.subarray(fileName, start, end);
		} else {
			return compilationResult.compilationUnit.getMainTypeName();
		}
	}

	public boolean isEmpty() {

		return (currentPackage == null) && (imports == null) && (types == null) && (statements==null);
	}

	public boolean isPackageInfo() {
		return CharOperation.equals(this.getMainTypeName(), TypeConstants.PACKAGE_INFO_NAME);
	}
	
	public boolean hasErrors() {
		return this.ignoreFurtherInvestigation;
	}

	public StringBuffer print(int indent, StringBuffer output) {

		if (currentPackage != null) {
			printIndent(indent, output).append("package "); //$NON-NLS-1$
			currentPackage.print(0, output, false).append(";\n"); //$NON-NLS-1$
		}
		if (imports != null)
			for (int i = 0; i < imports.length; i++) {
				printIndent(indent, output).append("import "); //$NON-NLS-1$
				ImportReference currentImport = imports[i];
				if (currentImport.isStatic()) {
					output.append("static "); //$NON-NLS-1$
				}
				currentImport.print(0, output).append(";\n"); //$NON-NLS-1$
			}

		if (types != null) {
			for (int i = 0; i < types.length; i++) {
				types[i].print(indent, output).append("\n"); //$NON-NLS-1$
			}
		}
		if (statements != null) {
			for (int i = 0; i < statements.length; i++) {
				statements[i].printStatement(indent, output).append("\n"); //$NON-NLS-1$
			}
		}
		return output;
	}
	
	/*
	 * Force inner local types to update their innerclass emulation
	 */
	public void propagateInnerEmulationForAllLocalTypes() {

		isPropagatingInnerClassEmulation = true;
		for (int i = 0, max = this.localTypeCount; i < max; i++) {
				
			LocalTypeBinding localType = localTypes[i];
			// only propagate for reachable local types
			if ((localType.classScope.referenceType().bits & IsReachable) != 0) {
				localType.updateInnerEmulationDependents();
			}
		}
	}

	public void recordStringLiteral(StringLiteral literal) {
		if (this.stringLiterals == null) {
			this.stringLiterals = new StringLiteral[STRING_LITERALS_INCREMENT];
			this.stringLiteralsPtr = 0;
		} else {
			int stackLength = this.stringLiterals.length;
			if (this.stringLiteralsPtr == stackLength) {
				System.arraycopy(
					this.stringLiterals,
					0,
					this.stringLiterals = new StringLiteral[stackLength + STRING_LITERALS_INCREMENT],
					0,
					stackLength);
			}
		}
		this.stringLiterals[this.stringLiteralsPtr++] = literal;		
	}

	/*
	 * Keep track of all local types, so as to update their innerclass
	 * emulation later on.
	 */
	public void record(LocalTypeBinding localType) {

		if (this.localTypeCount == 0) {
			this.localTypes = new LocalTypeBinding[5];
		} else if (this.localTypeCount == this.localTypes.length) {
			System.arraycopy(this.localTypes, 0, (this.localTypes = new LocalTypeBinding[this.localTypeCount * 2]), 0, this.localTypeCount);
		}
		this.localTypes[this.localTypeCount++] = localType;
	}

	public void resolve() {
		int startingTypeIndex = 0;
		boolean isPackageInfo =  false;//isPackageInfo();
		if (this.types != null && isPackageInfo) {
            // resolve synthetic type declaration
			final TypeDeclaration syntheticTypeDeclaration = types[0];
			// set empty javadoc to avoid missing warning (see bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=95286)
			if (syntheticTypeDeclaration.javadoc == null) {
				syntheticTypeDeclaration.javadoc = new Javadoc(syntheticTypeDeclaration.declarationSourceStart, syntheticTypeDeclaration.declarationSourceStart);
			}
			syntheticTypeDeclaration.resolve(this.scope);
			// resolve annotations if any
			if (this.currentPackage!= null && this.currentPackage.annotations != null) {
				resolveAnnotations(syntheticTypeDeclaration.staticInitializerScope, this.currentPackage.annotations, this.scope.getDefaultPackage());
			}
			// resolve javadoc package if any
			if (this.javadoc != null) {
				this.javadoc.resolve(syntheticTypeDeclaration.staticInitializerScope);
    		}
			startingTypeIndex = 1;
		} else {
			// resolve compilation unit javadoc package if any
			if (this.javadoc != null) {
				this.javadoc.resolve(this.scope);
    		}
		}
		if (this.currentPackage != null && this.currentPackage.annotations != null && !isPackageInfo) {
			scope.problemReporter().invalidFileNameForPackageAnnotations(this.currentPackage.annotations[0]);
		}
		try {
			if (types != null) {
				for (int i = startingTypeIndex, count = types.length; i < count; i++) {
					types[i].resolve(scope);
				}
			}
			if (statements != null) {
				for (int i = 0, count = statements.length; i < count; i++) {
					statements[i].resolve(scope);
				}
			}
			if (!this.compilationResult.hasErrors()) checkUnusedImports();
			reportNLSProblems();
		} catch (AbortCompilationUnit e) {
			this.ignoreFurtherInvestigation = true;
			return;
		}
	}

	private void reportNLSProblems() {
		if (this.nlsTags != null || this.stringLiterals != null) {
			final int stringLiteralsLength = this.stringLiteralsPtr;
			final int nlsTagsLength = this.nlsTags == null ? 0 : this.nlsTags.length;
			if (stringLiteralsLength == 0) {
				if (nlsTagsLength != 0) {
					for (int i = 0; i < nlsTagsLength; i++) {
						NLSTag tag = this.nlsTags[i];
						if (tag != null) {
							scope.problemReporter().unnecessaryNLSTags(tag.start, tag.end);
						}
					}
				}
			} else if (nlsTagsLength == 0) {
				// resize string literals
				if (this.stringLiterals.length != stringLiteralsLength) {
					System.arraycopy(this.stringLiterals, 0, (stringLiterals = new StringLiteral[stringLiteralsLength]), 0, stringLiteralsLength);
				}
				Arrays.sort(this.stringLiterals, STRING_LITERAL_COMPARATOR);
				for (int i = 0; i < stringLiteralsLength; i++) {
					scope.problemReporter().nonExternalizedStringLiteral(this.stringLiterals[i]);
				}
			} else {
				// need to iterate both arrays to find non matching elements
				if (this.stringLiterals.length != stringLiteralsLength) {
					System.arraycopy(this.stringLiterals, 0, (stringLiterals = new StringLiteral[stringLiteralsLength]), 0, stringLiteralsLength);
				}
				Arrays.sort(this.stringLiterals, STRING_LITERAL_COMPARATOR);
				int indexInLine = 1;
				int lastLineNumber = -1;
				StringLiteral literal = null;
				int index = 0;
				int i = 0;
				stringLiteralsLoop: for (; i < stringLiteralsLength; i++) {
					literal = this.stringLiterals[i];
					final int literalLineNumber = literal.lineNumber;
					if (lastLineNumber != literalLineNumber) {
						indexInLine = 1;
						lastLineNumber = literalLineNumber;
					} else {
						indexInLine++;
					}
					if (index < nlsTagsLength) {
						nlsTagsLoop: for (; index < nlsTagsLength; index++) {
							NLSTag tag = this.nlsTags[index];
							if (tag == null) continue nlsTagsLoop;
							int tagLineNumber = tag.lineNumber;
							if (literalLineNumber < tagLineNumber) {
								scope.problemReporter().nonExternalizedStringLiteral(literal);
								continue stringLiteralsLoop;
							} else if (literalLineNumber == tagLineNumber) {
								if (tag.index == indexInLine) {
									this.nlsTags[index] = null;
									index++;
									continue stringLiteralsLoop;
								} else {
									nlsTagsLoop2: for (int index2 = index + 1; index2 < nlsTagsLength; index2++) {
										NLSTag tag2 = this.nlsTags[index2];
										if (tag2 == null) continue nlsTagsLoop2;
										int tagLineNumber2 = tag2.lineNumber;
										if (literalLineNumber == tagLineNumber2) {
											if (tag2.index == indexInLine) {
												this.nlsTags[index2] = null;
												continue stringLiteralsLoop;
											} else {
												continue nlsTagsLoop2;
											}
										} else {
											scope.problemReporter().nonExternalizedStringLiteral(literal);
											continue stringLiteralsLoop;
										}
									}
									scope.problemReporter().nonExternalizedStringLiteral(literal);
									continue stringLiteralsLoop;
								}
							} else {
								scope.problemReporter().unnecessaryNLSTags(tag.start, tag.end);
								continue nlsTagsLoop;
							}
						}
					}
					// all nls tags have been processed, so remaining string literals are not externalized
					break stringLiteralsLoop;
				}
				for (; i < stringLiteralsLength; i++) {
					scope.problemReporter().nonExternalizedStringLiteral(this.stringLiterals[i]);
				}
				if (index < nlsTagsLength) {
					for (; index < nlsTagsLength; index++) {
						NLSTag tag = this.nlsTags[index];
						if (tag != null) {
							scope.problemReporter().unnecessaryNLSTags(tag.start, tag.end);
						}
					}						
				}
			}
		}
	}

	public void tagAsHavingErrors() {
		ignoreFurtherInvestigation = true;
	}

	
	
	public void traverse(
			ASTVisitor visitor,
			CompilationUnitScope unitScope) {
		traverse(visitor, scope,false);
	}
	
	public void traverse(
		ASTVisitor visitor,
		CompilationUnitScope unitScope, boolean ignoreErrors) {

		if (ignoreFurtherInvestigation && !ignoreErrors)
			return;
		try {
			if (visitor.visit(this, this.scope)) {
				if (this.types != null && isPackageInfo()) {
		            // resolve synthetic type declaration
					final TypeDeclaration syntheticTypeDeclaration = types[0];
					// resolve javadoc package if any
					final MethodScope methodScope = syntheticTypeDeclaration.staticInitializerScope;
					if (this.javadoc != null) {
						this.javadoc.traverse(visitor, methodScope);
					}
					if (this.currentPackage != null) {
					final Annotation[] annotations = this.currentPackage.annotations;
					if (annotations != null) {
						int annotationsLength = annotations.length;
						for (int i = 0; i < annotationsLength; i++) {
							annotations[i].traverse(visitor, methodScope);
						}
					}
					}
				}
				
//				if (currentPackage != null) {
//					currentPackage.traverse(visitor, this.scope);
//				}
//				if (imports != null) {
//					int importLength = imports.length;
//					for (int i = 0; i < importLength; i++) {
//						imports[i].traverse(visitor, this.scope);
//					}
//				}
//				if (types != null) {
//					int typesLength = types.length;
//					for (int i = 0; i < typesLength; i++) {
//						types[i].traverse(visitor, this.scope);
//					}
//				}
				if (statements != null) {
					int statementsLength = statements.length;
					for (int i = 0; i < statementsLength; i++) {
						statements[i].traverse(visitor, this.scope);
					}
				}
				traverseInferredTypes(visitor,unitScope);
			}
			visitor.endVisit(this, this.scope);
		} catch (AbortCompilationUnit e) {
			// ignore
		}
	}

	public void traverseInferredTypes(ASTVisitor visitor,BlockScope unitScope) {
		boolean continueVisiting=true;
		for (int i=0;i<this.numberInferredTypes;i++) {
			InferredType inferredType = this.inferredTypes[i];
			continueVisiting=visitor.visit(inferredType, scope);
			  for (int attributeInx=0; attributeInx<inferredType.numberAttributes; attributeInx++) {
					visitor.visit(inferredType.attributes[attributeInx], scope);
				}
			if (inferredType.methods!=null)
				for (Iterator iterator = inferredType.methods.iterator();  continueVisiting && iterator
						.hasNext();) {
					InferredMethod inferredMethod = (InferredMethod) iterator.next();
					visitor.visit(inferredMethod, scope);
				}
			visitor.endVisit(inferredType, scope);
		}
	}
	
	public InferredType findInferredType(char [] name)
	{
		return (InferredType)inferredTypesHash.get(name);
//		for (int i=0;i<this.numberInferredTypes;i++) {
//			InferredType inferredType = this.inferredTypes[i];
//			if (CharOperation.equals(name,inferredType.getName()))
//					return inferredType; 
//		}
//		return null;
	}
	
	
	
	public void printInferredTypes(StringBuffer sb)
	{
		for (int i=0;i<this.numberInferredTypes;i++) {
			InferredType inferredType = this.inferredTypes[i];
				if (inferredType.isDefinition)
				{
					inferredType.print(0,sb);
					sb.append("\n");
				}
		}
		
	}
}
