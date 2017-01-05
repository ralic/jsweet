/* 
 * JSweet transpiler - http://www.jsweet.org
 * Copyright (C) 2015 CINCHEO SAS <renaud.pawlak@cincheo.fr>
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *  
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jsweet.transpiler;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.File;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.jsweet.JSweetConfig;
import org.jsweet.transpiler.OverloadScanner.Overload;
import org.jsweet.transpiler.typescript.Java2TypeScriptTranslator;
import org.jsweet.transpiler.util.DirectedGraph;

import com.sun.tools.javac.code.Attribute.Compound;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.PackageSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree.JCWildcard;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;

/**
 * The transpiler context, which is an extension of the Java compiler context.
 * 
 * @author Renaud Pawlak
 */
public class JSweetContext extends Context {

	protected static Logger logger = Logger.getLogger(Java2TypeScriptTranslator.class);

	private static class AnnotationFilterDescriptor {
		public final Collection<Pattern> inclusionPatterns;
		public final Collection<Pattern> exclusionPatterns;
		public final String parameter;

		public AnnotationFilterDescriptor(Collection<Pattern> inclusionPatterns, Collection<Pattern> exclusionPatterns,
				String parameter) {
			super();
			this.inclusionPatterns = inclusionPatterns;
			this.exclusionPatterns = exclusionPatterns;
			this.parameter = parameter;
		}

		@Override
		public String toString() {
			return "FILTER" + (parameter == null ? "" : "('" + parameter + "')") + ": INCLUDES=" + inclusionPatterns
					+ ", EXCLUDES=" + exclusionPatterns;
		}
	}

	private String toRegexp(String pattern) {
		return pattern.replace("(", "\\(").replace(")", "\\)").replace(".", "\\.").replace("*", ".*");
	}

	private Pattern annotationWithParameterPattern = Pattern.compile("@([^(]*)\\(([^)]*)\\)");
	private Map<String, Collection<AnnotationFilterDescriptor>> annotationFilters = new HashMap<>();

	private Collection<AnnotationFilterDescriptor> getAnnotationFilterDescriptors(String annotationType) {
		Collection<AnnotationFilterDescriptor> descrs = annotationFilters.get(annotationType);
		if (descrs == null) {
			descrs = new ArrayList<>();
			annotationFilters.put(annotationType, descrs);
		}
		return descrs;
	}

	/**
	 * Creates a new JSweet transpilation context.
	 * 
	 * @param options
	 *            the JSweet transpilation options
	 */
	public JSweetContext(JSweetOptions options) {
		this.options = options;
		if (options.getConfiguration() != null) {
			for (Entry<String, Map<String, Object>> entry : options.getConfiguration().entrySet()) {
				if (entry.getKey().startsWith("@")) {
					String annotationType = null;
					Matcher m = annotationWithParameterPattern.matcher(entry.getKey());
					String parameter = null;
					if (m.matches()) {
						annotationType = JSweetConfig.LANG_PACKAGE + "." + m.group(1);
						parameter = m.group(2);
					} else {
						annotationType = JSweetConfig.LANG_PACKAGE + "." + entry.getKey().substring(1);
					}
					Object include = entry.getValue().get("include");
					Collection<AnnotationFilterDescriptor> filterDescriptors = getAnnotationFilterDescriptors(
							annotationType);
					Collection<Pattern> inclusionPatterns = null;
					Collection<Pattern> exclusionPatterns = null;
					if (include != null) {
						inclusionPatterns = new ArrayList<>();
						if (include instanceof Collection) {
							for (Object o : (Collection<?>) include) {
								try {
									inclusionPatterns.add(Pattern.compile(toRegexp(o.toString())));
								} catch (Exception e) {
									logger.warn("invalid pattern '" + o + "' for " + entry.getKey() + ".include");
								}
							}
						} else {
							try {
								inclusionPatterns.add(Pattern.compile(toRegexp(include.toString())));
							} catch (Exception e) {
								logger.warn("invalid pattern '" + include + "' for " + entry.getKey() + ".include");
							}
						}
					} else {
						logger.warn(
								"annotation entry " + entry.getKey() + " does not have a mandatory 'include' entry");
					}
					Object exclude = entry.getValue().get("exclude");
					if (exclude != null) {
						exclusionPatterns = new ArrayList<>();
						if (exclude instanceof Collection) {
							for (Object o : (Collection<?>) exclude) {
								try {
									exclusionPatterns.add(Pattern.compile(toRegexp(o.toString())));
								} catch (Exception e) {
									logger.warn("invalid pattern '" + o + "' for " + entry.getKey() + ".exclude");
								}
							}
						} else {
							try {
								exclusionPatterns.add(Pattern.compile(toRegexp(exclude.toString())));
							} catch (Exception e) {
								logger.warn("invalid pattern '" + exclude + "' for " + entry.getKey() + ".exclude");
							}
						}
					}
					filterDescriptors
							.add(new AnnotationFilterDescriptor(inclusionPatterns, exclusionPatterns, parameter));

				}
			}
		}
		for (Entry<String, Collection<AnnotationFilterDescriptor>> e : annotationFilters.entrySet()) {
			logger.info("annotation filter descriptor: " + e);
		}
	}

	/**
	 * JSweet transpilation options.
	 */
	public final JSweetOptions options;

	/**
	 * A cache of method overloads.
	 * 
	 * @see OverloadScanner
	 * @see OverloadScanner.Overload
	 */
	private Map<ClassSymbol, Map<String, Overload>> overloads = new HashMap<>();

	/**
	 * A cache of static method overloads.
	 * 
	 * @see OverloadScanner
	 * @see OverloadScanner.Overload
	 */
	private Map<ClassSymbol, Map<String, Overload>> staticOverloads = new HashMap<>();

	/**
	 * Returns all the overloads in this context.
	 */
	public Set<Overload> getAllOverloads() {
		Set<Overload> result = new HashSet<>();
		overloads.values().forEach(m -> result.addAll(m.values()));
		staticOverloads.values().forEach(m -> result.addAll(m.values()));
		return result;
	}

	/**
	 * Gets or create an overload instance for the given class and method.
	 */
	public Overload getOrCreateOverload(ClassSymbol clazz, MethodSymbol method) {
		Map<ClassSymbol, Map<String, Overload>> actualOverloads = method.isStatic() ? staticOverloads : overloads;
		Map<String, Overload> m = actualOverloads.get(clazz);
		if (m == null) {
			m = new HashMap<>();
			actualOverloads.put(clazz, m);
		}
		String name = method.name.toString();
		Overload overload = m.get(name);
		if (overload == null) {
			overload = new Overload();
			overload.methodName = name;
			m.put(name, overload);
		}
		return overload;
	}

	/**
	 * Gets an overload instance for the given class and method.
	 */
	public Overload getOverload(ClassSymbol clazz, MethodSymbol method) {
		Map<ClassSymbol, Map<String, Overload>> actualOverloads = method.isStatic() ? staticOverloads : overloads;
		Map<String, Overload> m = actualOverloads.get(clazz);
		if (m == null) {
			return null;
		}
		Overload overload = m.get(method.name.toString());
		if (overload == null) {
			return null;
		}
		return overload;
	}

	/**
	 * Tells if that method is part of an invalid overload in its declaring
	 * class.
	 */
	public boolean isInvalidOverload(MethodSymbol method) {
		Overload overload = getOverload((ClassSymbol) method.getEnclosingElement(), method);
		return overload != null && !overload.isValid;
	}

	/**
	 * Contains the classes that have a wrong constructor overload.
	 */
	public Set<ClassSymbol> classesWithWrongConstructorOverload = new HashSet<>();

	/**
	 * The Java compiler symbol table for fast access.
	 */
	public Symtab symtab;

	/**
	 * The Java compiler names for fast access.
	 */
	public Names names;

	/**
	 * The Java compiler types for fast access.
	 */
	public Types types;

	/**
	 * A flag to tell if the transpiler is in module mode or not.
	 */
	public boolean useModules = false;

	/**
	 * The source files that are being transpiled.
	 */
	public SourceFile[] sourceFiles;

	private List<String> usedModules = new ArrayList<>();

	/**
	 * A flag that indicates if we are building a bundle. In bundle mode, all
	 * type names are expanded (fully qualified) and static fields will be
	 * initalized at the end of the bundle.
	 */
	public boolean bundleMode = false;

	/**
	 * Holds all the static fields that are lazy intitialized.
	 */
	public Set<VarSymbol> lazyInitializedStatics = new HashSet<>();

	private Map<ClassSymbol, Integer> staticInitializerCounts = new HashMap<>();

	/**
	 * Increments the count of static initialization blocks for the given class.
	 */
	public void countStaticInitializer(ClassSymbol clazz) {
		staticInitializerCounts.put(clazz,
				(staticInitializerCounts.containsKey(clazz) ? staticInitializerCounts.get(clazz) : 0) + 1);
	}

	/**
	 * Gets the static initializer count for the given class.
	 */
	public int getStaticInitializerCount(ClassSymbol clazz) {
		Integer count = null;
		return (count = staticInitializerCounts.get(clazz)) == null ? 0 : count;
	}

	/**
	 * Register a module that is used by the transpiled program.
	 * 
	 * @param moduleName
	 *            the module being used
	 */
	public void registerUsedModule(String moduleName) {
		if (!usedModules.contains(moduleName)) {
			usedModules.add(moduleName);
		}
	}

	/**
	 * The list of modules used by the transpiled program.
	 */
	public List<String> getUsedModules() {
		return usedModules;
	}

	private Map<String, Set<String>> importedNamesInModules = new HashMap<>();
	private Map<String, Map<Symbol, String>> importedElementsInModules = new HashMap<>();

	/**
	 * Register a name that is imported by the given package of the transpiled
	 * program.
	 * 
	 * <pre>
	 * import targetName = require("sourceName");
	 * </pre>
	 * 
	 * @param moduleName
	 *            the module that is importing the name
	 * @param sourceElement
	 *            the source element if any (null if not applicable)
	 * @param targetName
	 *            the target name being imported
	 */
	public void registerImportedName(String moduleName, Symbol sourceElement, String targetName) {
		Set<String> importedNames = importedNamesInModules.get(moduleName);
		if (importedNames == null) {
			importedNames = new HashSet<>();
			importedNamesInModules.put(moduleName, importedNames);
		}
		if (!importedNames.contains(targetName)) {
			importedNames.add(targetName);
		}
		if (sourceElement != null) {
			Map<Symbol, String> importedElements = importedElementsInModules.get(moduleName);
			if (importedElements == null) {
				importedElements = new HashMap<>();
				importedElementsInModules.put(moduleName, importedElements);
			}
			if (!importedElements.containsKey(sourceElement)) {
				importedElements.put(sourceElement, targetName);
			}

		}
	}

	/**
	 * The list of names imported by the given module of the transpiled program.
	 */
	public Set<String> getImportedNames(String moduleName) {
		Set<String> importedNames = importedNamesInModules.get(moduleName);
		if (importedNames == null) {
			importedNames = new HashSet<>();
			importedNamesInModules.put(moduleName, importedNames);
		}
		return importedNames;
	}

	/**
	 * The list of package names imported by the given m of the transpiled
	 * program.
	 */
	public Map<Symbol, String> getImportedElements(String moduleName) {
		Map<Symbol, String> importedElements = importedElementsInModules.get(moduleName);
		if (importedElements == null) {
			importedElements = new HashMap<>();
			importedElementsInModules.put(moduleName, importedElements);
		}
		return importedElements;
	}

	/**
	 * Clears the names imported by the given module.
	 */
	public void clearImportedNames(String moduleName) {
		Set<String> importedNames = new HashSet<>();
		importedNamesInModules.put(moduleName, importedNames);
		Map<Symbol, String> importedModulesForNames = new HashMap<>();
		importedElementsInModules.put(moduleName, importedModulesForNames);
	}

	private Map<String, List<Symbol>> exportedElements = new HashMap<>();

	/**
	 * Gets the exported elements for all the modules defined in the program.
	 */
	public Map<String, List<Symbol>> getExportedElements() {
		return exportedElements;
	}

	/**
	 * Adds an exported element for a module.
	 */
	public void addExportedElement(String moduleName, Symbol exportedElement) {
		List<Symbol> exportedNamesForModule = exportedElements.get(moduleName);
		if (exportedNamesForModule == null) {
			exportedNamesForModule = new ArrayList<Symbol>();
			exportedElements.put(moduleName, exportedNamesForModule);
		}
		exportedNamesForModule.add(exportedElement);
	}

	/**
	 * Source files containing a main method.
	 */
	public List<File> entryFiles = new ArrayList<>();

	/**
	 * A graph containing the module dependencies when using modules (empty
	 * otherwise).
	 */
	public DirectedGraph<PackageSymbol> packageDependencies = new DirectedGraph<>();

	/**
	 * Stores the root package names (i.e. packages contained in the default
	 * package or in a package annotated with the {@link jsweet.lang.Root}
	 * annotation).
	 */
	public Set<String> topLevelPackageNames = new HashSet<>();

	/**
	 * Store root packages (i.e. packages contained in the default package or in
	 * a package annotated with the {@link jsweet.lang.Root} annotation,
	 * including null, i.e. default package).
	 */
	public HashSet<PackageSymbol> rootPackages = new HashSet<>();

	/**
	 * A flag to keep track of wether a multiple root packages problem was
	 * already reported (shall report only once).
	 */
	public boolean reportedMultipleRootPackages = false;

	/**
	 * Globally imported name (in the global namespace).
	 */
	public Set<String> globalImports = new HashSet<>();

	/**
	 * Imported top packages (used to avoid clashes with local variables when
	 * bundle is on).
	 */
	public Set<String> importedTopPackages = new HashSet<>();

	/**
	 * A flag that indicates if the transpilation is in "strict" mode, which
	 * means that the <code>jsweet-core-strict</code> jar is in the classpath.
	 */
	public boolean strictMode = false;

	private List<String> footerStatements = new LinkedList<String>();

	/**
	 * Gets and clears the footer statements.
	 */
	public String poolFooterStatements() {
		StringBuilder sb = new StringBuilder();
		for (String footerStatement : footerStatements) {
			sb.append("\n");
			sb.append(footerStatement);
			sb.append("\n");
		}
		footerStatements.clear();
		return sb.toString();
	}

	/**
	 * Adds a footer statement.
	 */
	public void addFooterStatement(String footerStatement) {
		footerStatements.add(footerStatement);
	}

	/**
	 * Adds a footer statement at the first position.
	 */
	public void addTopFooterStatement(String footerStatement) {
		footerStatements.add(0, footerStatement);
	}

	private Map<String, String> globalsMapping = new HashMap<>();

	/**
	 * Adds a globals mapping.
	 */
	public void addGlobalsMapping(String from, String to) {
		globalsMapping.put(from, to);
	}

	/**
	 * Returns the TypeScript string for globals mapping.
	 */
	public String getGlobalsMappingString() {
		StringBuilder b = new StringBuilder();
		for (Map.Entry<String, String> e : globalsMapping.entrySet()) {
			b.append("var " + e.getValue() + " = " + e.getKey() + ";\n");
		}
		return b.toString();
	}

	private Map<TypeSymbol, Set<Entry<JCClassDecl, JCMethodDecl>>> defaultMethods = new HashMap<>();
	private Map<JCMethodDecl, JCCompilationUnit> defaultMethodsCompilationUnits = new HashMap<>();

	/**
	 * Gets the default methods declared in the given type.
	 */
	public Set<Entry<JCClassDecl, JCMethodDecl>> getDefaultMethods(TypeSymbol type) {
		return defaultMethods.get(type);
	}

	/**
	 * Stores a default method AST for the given type.
	 */
	public void addDefaultMethod(JCCompilationUnit compilationUnit, JCClassDecl type, JCMethodDecl defaultMethod) {
		Set<Entry<JCClassDecl, JCMethodDecl>> methods = defaultMethods.get(type.sym);
		if (methods == null) {
			methods = new HashSet<>();
			defaultMethods.put(type.sym, methods);
		}
		methods.add(new AbstractMap.SimpleEntry<>(type, defaultMethod));
		defaultMethodsCompilationUnits.put(defaultMethod, compilationUnit);
	}

	/**
	 * Gets the compilation unit the given default method belongs to.
	 */
	public JCCompilationUnit getDefaultMethodCompilationUnit(JCMethodDecl defaultMethod) {
		return defaultMethodsCompilationUnits.get(defaultMethod);
	}

	private Map<VarSymbol, String> fieldNameMapping = new HashMap<>();

	/**
	 * Adds a name mapping to a field (rename it to avoid name clashes).
	 */
	public void addFieldNameMapping(VarSymbol field, String name) {
		fieldNameMapping.put(field, name);
	}

	/**
	 * Gets a field name mapping if any (null otherwise).
	 */
	public String getFieldNameMapping(Symbol field) {
		return fieldNameMapping.get(field);
	}

	/**
	 * Tells JSweet to ignore wildcard bounds. For instance if ignored:
	 * 
	 * <pre>
	 * void f(C<? extends String> c)
	 * </pre>
	 * 
	 * will transpile to:
	 * 
	 * <pre>
	 * f(c: C<any>)
	 * </pre>
	 * 
	 * otherwise:
	 * 
	 * <pre>
	 * void f(C<? extends String> c)
	 * </pre>
	 * 
	 * will transpile to:
	 * 
	 * <pre>
	 * f<__T1 extends string>(c: C<__T1>)
	 * </pre>
	 * 
	 */
	public boolean ignoreWildcardBounds = true;

	private Map<JCWildcard, String> wildcardNames = new HashMap<>();

	private Map<Symbol, List<JCWildcard>> wildcards = new HashMap<>();

	/**
	 * Registers a wilcard for a given container (type parameterized element).
	 */
	public void registerWildcard(Symbol holder, JCWildcard wildcard) {
		if (wildcard.getBound() == null) {
			return;
		}
		List<JCWildcard> l = wildcards.get(holder);
		if (l == null) {
			l = new ArrayList<>();
			wildcards.put(holder, l);
		}
		l.add(wildcard);
		wildcardNames.put(wildcard, "__T" + l.size());
	}

	/**
	 * Gets the wildcard name if any.
	 */
	public String getWildcardName(JCWildcard wildcard) {
		return wildcardNames.get(wildcard);
	}

	/**
	 * Gets the registered wildcards for the given type parameter holder.
	 */
	public List<JCWildcard> getWildcards(Symbol holder) {
		return wildcards.get(holder);
	}

	private static Pattern libPackagePattern = Pattern.compile(JSweetConfig.LIBS_PACKAGE + "\\.[^.]*");

	/**
	 * Returns true if the given symbol is a root package (annotated with @Root
	 * or a definition package).
	 */
	public boolean isRootPackage(Symbol symbol) {
		return hasAnnotationType(symbol, JSweetConfig.ANNOTATION_ROOT) || (symbol instanceof PackageSymbol
				&& libPackagePattern.matcher(symbol.getQualifiedName().toString()).matches());
	}

	/**
	 * Tells if the given type is a Java interface.
	 */
	public boolean isInterface(TypeSymbol typeSymbol) {
		return (typeSymbol.type.isInterface() || hasAnnotationType(typeSymbol, JSweetConfig.ANNOTATION_INTERFACE));
	}

	/**
	 * Tells if the given symbol is annotated with one of the given annotation
	 * types.
	 */
	public boolean hasAnnotationType(Symbol symbol, String... annotationTypes) {
		if (options.getConfiguration() != null) {
			String signature = symbol.toString();
			if (symbol.getEnclosingElement() != null) {
				signature = symbol.getEnclosingElement().getQualifiedName().toString() + "." + signature;
			}
			for (String annotationType : annotationTypes) {
				Collection<AnnotationFilterDescriptor> filterDescriptors = annotationFilters.get(annotationType);
				if (filterDescriptors != null) {
					for (AnnotationFilterDescriptor filterDescriptor : filterDescriptors) {
						for (Pattern include : filterDescriptor.inclusionPatterns) {
							if (include.matcher(signature).matches()) {
								boolean excluded = false;
								Collection<Pattern> excludePatterns = filterDescriptor.exclusionPatterns;
								if (excludePatterns != null) {
									for (Pattern exclude : excludePatterns) {
										if (exclude.matcher(signature).matches()) {
											excluded = true;
											break;
										}
									}
								}
								if (!excluded) {
									return true;
								}
							}
						}
					}
				}
			}
		}
		return hasActualAnnotationType(symbol, annotationTypes);
	}

	/**
	 * Gets the actual name of a symbol from a JSweet convention, so including
	 * potential <code>jsweet.lang.Name</code> annotation.
	 */
	public String getActualName(Symbol symbol) {
		String name = symbol.getSimpleName().toString();
		if (hasAnnotationType(symbol, JSweetConfig.ANNOTATION_NAME)) {
			String originalName = getAnnotationValue(symbol, JSweetConfig.ANNOTATION_NAME, null);
			if (!isBlank(originalName)) {
				name = originalName;
			}
		}
		return name;
	}

	private void getRootRelativeName(Map<Symbol, String> nameMapping, StringBuilder sb, Symbol symbol) {
		if (!isRootPackage(symbol)) {
			if (sb.length() > 0 && !"".equals(symbol.toString())) {
				sb.insert(0, ".");
			}

			String name = symbol.getSimpleName().toString();

			if (nameMapping != null && nameMapping.containsKey(symbol)) {
				name = nameMapping.get(symbol);
			} else {
				if (hasAnnotationType(symbol, JSweetConfig.ANNOTATION_NAME)) {
					String originalName = getAnnotationValue(symbol, JSweetConfig.ANNOTATION_NAME, null);
					if (!isBlank(originalName)) {
						name = originalName;
					}
				}
			}
			sb.insert(0, name);
			symbol = (symbol instanceof PackageSymbol) ? ((PackageSymbol) symbol).owner : symbol.getEnclosingElement();
			if (symbol != null) {
				getRootRelativeName(nameMapping, sb, symbol);
			}
		}
	}

	/**
	 * Gets the top-level package enclosing the given symbol. The top-level
	 * package is the one that is enclosed within a root package (see
	 * <code>jsweet.lang.Root</code>) or the one in the default (unnamed)
	 * package.
	 */
	public PackageSymbol getTopLevelPackage(Symbol symbol) {
		if ((symbol instanceof PackageSymbol) && isRootPackage(symbol)) {
			return null;
		}
		Symbol parent = (symbol instanceof PackageSymbol) ? ((PackageSymbol) symbol).owner
				: symbol.getEnclosingElement();
		if (parent != null && isRootPackage(parent)) {
			if (symbol instanceof PackageSymbol) {
				return (PackageSymbol) symbol;
			} else {
				return null;
			}
		} else {
			if (parent == null || (parent instanceof PackageSymbol && StringUtils.isBlank(parent.getSimpleName()))) {
				if (symbol instanceof PackageSymbol) {
					return (PackageSymbol) symbol;
				} else {
					return null;
				}
			} else {
				return getTopLevelPackage(parent);
			}
		}
	}

	/**
	 * Finds the first (including itself) enclosing package annotated
	 * with @Root.
	 */
	public PackageSymbol getFirstEnclosingRootPackage(PackageSymbol packageSymbol) {
		if (packageSymbol == null) {
			return null;
		}
		if (isRootPackage(packageSymbol)) {
			return packageSymbol;
		}
		return getFirstEnclosingRootPackage((PackageSymbol) packageSymbol.owner);
	}

	private void getRootRelativeJavaName(StringBuilder sb, Symbol symbol) {
		if (!isRootPackage(symbol)) {
			if (sb.length() > 0 && !"".equals(symbol.toString())) {
				sb.insert(0, ".");
			}

			String name = symbol.getSimpleName().toString();

			sb.insert(0, name);
			symbol = (symbol instanceof PackageSymbol) ? ((PackageSymbol) symbol).owner : symbol.getEnclosingElement();
			if (symbol != null) {
				getRootRelativeJavaName(sb, symbol);
			}
		}
	}

	/**
	 * Gets the qualified name of a symbol relatively to the root package
	 * (potentially annotated with <code>jsweet.lang.Root</code>).
	 * 
	 * @param nameMapping
	 *            a map to redirect names
	 * @param symbol
	 *            the symbol to get the name of
	 * @param useJavaNames
	 *            if true uses plain Java names, if false uses
	 *            <code>jsweet.lang.Name</code> annotations
	 * @return
	 */
	public String getRootRelativeName(Map<Symbol, String> nameMapping, Symbol symbol, boolean useJavaNames) {
		if (useJavaNames) {
			return getRootRelativeJavaName(symbol);
		} else {
			return getRootRelativeName(nameMapping, symbol);
		}
	}

	/**
	 * Gets the qualified name of a symbol relatively to the root package
	 * (potentially annotated with <code>jsweet.lang.Root</code>). This function
	 * takes into account potential <code>jsweet.lang.Name</code> annotations).
	 */
	public String getRootRelativeName(Map<Symbol, String> nameMapping, Symbol symbol) {
		StringBuilder sb = new StringBuilder();
		getRootRelativeName(nameMapping, sb, symbol);
		if (sb.length() > 0 && sb.charAt(0) == '.') {
			sb.deleteCharAt(0);
		}
		return sb.toString();
	}

	/**
	 * Gets the qualified name of a symbol relatively to the root package
	 * (potentially annotated with <code>jsweet.lang.Root</code>). This function
	 * ignores <code>jsweet.lang.Name</code> annotations).
	 */
	public String getRootRelativeJavaName(Symbol symbol) {
		StringBuilder sb = new StringBuilder();
		getRootRelativeJavaName(sb, symbol);
		return sb.toString();
	}

	/**
	 * Gets the first value of the 'value' property for the given annotation
	 * type if found on the given symbol.
	 */
	public String getAnnotationValue(Symbol symbol, String annotationType, String defaultValue) {
		if (options.getConfiguration() != null) {
			String signature = symbol.toString();
			if (symbol.getEnclosingElement() != null) {
				signature = symbol.getEnclosingElement().getQualifiedName().toString() + "." + signature;
			}
			Collection<AnnotationFilterDescriptor> filterDescriptors = annotationFilters.get(annotationType);
			if (filterDescriptors != null) {
				for (AnnotationFilterDescriptor filterDescriptor : filterDescriptors) {
					for (Pattern include : filterDescriptor.inclusionPatterns) {
						if (include.matcher(signature).matches()) {
							boolean excluded = false;
							Collection<Pattern> excludePatterns = filterDescriptor.exclusionPatterns;
							if (excludePatterns != null) {
								for (Pattern exclude : excludePatterns) {
									if (exclude.matcher(signature).matches()) {
										excluded = true;
										break;
									}
								}
							}
							if (!excluded) {
								if (filterDescriptor.parameter == null) {
									return defaultValue;
								} else if (filterDescriptor.parameter.startsWith("'")) {
									return filterDescriptor.parameter.substring(1,
											filterDescriptor.parameter.length() - 1);
								} else if (filterDescriptor.parameter.endsWith(".class")) {
									return filterDescriptor.parameter.substring(0,
											filterDescriptor.parameter.length() - 6);
								} else {
									return filterDescriptor.parameter;
								}
							}
						}
					}
				}
			}
		}

		AnnotationMirror anno = getAnnotation(symbol, annotationType);
		String val = defaultValue;
		if (anno != null) {
			val = "" + getFirstAnnotationValue(anno, defaultValue);
		}
		return val;
	}

	/**
	 * Gets the first value of the 'value' property.
	 */
	private static Object getFirstAnnotationValue(AnnotationMirror annotation, Object defaultValue) {
		for (AnnotationValue value : annotation.getElementValues().values()) {
			return value.getValue();
		}
		return defaultValue;
	}

	// /**
	// * Gets the value of the given annotation property.
	// *
	// * @param annotation
	// * the annotation
	// * @param propertyName
	// * the name of the annotation property to get the value of
	// * @param defaultValue
	// * the value to return if not found
	// */
	// @SuppressWarnings("unchecked")
	// private static <T> T getAnnotationValue(AnnotationMirror annotation,
	// String propertyName, T defaultValue) {
	// for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue>
	// annoProperty : annotation
	// .getElementValues().entrySet()) {
	// if
	// (annoProperty.getKey().getSimpleName().toString().equals(propertyName)) {
	// return (T) annoProperty.getValue().getValue();
	// }
	// }
	// return defaultValue;
	// }

	/**
	 * Gets the symbol's annotation that correspond to the given annotation type
	 * name if exists.
	 */
	private static AnnotationMirror getAnnotation(Symbol symbol, String annotationType) {
		for (Compound a : symbol.getAnnotationMirrors()) {
			if (annotationType.equals(a.type.toString())) {
				return a;
			}
		}
		return null;
	}

	// /**
	// * Gets the annotation tree that matches the given type name.
	// */
	// private static JCAnnotation getAnnotation(List<JCAnnotation> annotations,
	// String annotationType) {
	// for (JCAnnotation a : annotations) {
	// if (annotationType.equals(a.type.toString())) {
	// return a;
	// }
	// }
	// return null;
	// }

	/**
	 * Grabs the names of all the support interfaces in the class and interface
	 * hierarchy.
	 */
	public void grabSupportedInterfaceNames(Set<String> interfaces, TypeSymbol type) {
		if (type == null) {
			return;
		}
		if (isInterface(type)) {
			interfaces.add(type.getQualifiedName().toString());
		}
		if (type instanceof ClassSymbol) {
			for (Type t : ((ClassSymbol) type).getInterfaces()) {
				grabSupportedInterfaceNames(interfaces, t.tsym);
			}
			grabSupportedInterfaceNames(interfaces, ((ClassSymbol) type).getSuperclass().tsym);
		}
	}

	public void grabMethodsToBeImplemented(List<MethodSymbol> methods, TypeSymbol type) {
		if (type == null) {
			return;
		}
		if (isInterface(type)) {
			for (Symbol s : type.getEnclosedElements()) {
				if (s instanceof MethodSymbol) {
					methods.add((MethodSymbol) s);
				}
			}
		}
		if (type instanceof ClassSymbol) {
			for (Type t : ((ClassSymbol) type).getInterfaces()) {
				grabMethodsToBeImplemented(methods, t.tsym);
			}
			grabMethodsToBeImplemented(methods, ((ClassSymbol) type).getSuperclass().tsym);
		}
	}

	/**
	 * Tells if the given symbol is annotated with one of the given annotation
	 * type names.
	 */
	private static boolean hasActualAnnotationType(Symbol symbol, String... annotationTypes) {
		for (Compound a : symbol.getAnnotationMirrors()) {
			for (String annotationType : annotationTypes) {
				if (annotationType.equals(a.type.toString())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns true if this new class expression defines an anonymous class.
	 */
	public boolean isAnonymousClass(JCNewClass newClass) {
		if (newClass.clazz != null && newClass.def != null) {
			if (hasAnnotationType(newClass.clazz.type.tsym, JSweetConfig.ANNOTATION_OBJECT_TYPE)
					|| hasAnnotationType(newClass.clazz.type.tsym, JSweetConfig.ANNOTATION_INTERFACE)) {
				return false;
			}
			if (newClass.def.defs.size() > 2) {
				// a map has a constructor (implicit) and an initializer
				return true;
			}
			for (JCTree def : newClass.def.defs) {
				if (def instanceof JCVariableDecl) {
					// no variables in maps
					return true;
				}
				if (def instanceof JCMethodDecl && !((JCMethodDecl) def).sym.isConstructor()) {
					// no regular methods in maps
					return true;
				}
				if (def instanceof JCBlock) {
					for (JCStatement s : ((JCBlock) def).stats) {
						if (!isAllowedStatementInMap(s)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private static boolean isAllowedStatementInMap(JCStatement statement) {
		if (statement instanceof JCExpressionStatement) {
			JCExpressionStatement exprStat = (JCExpressionStatement) statement;
			if (exprStat.getExpression() instanceof JCAssign) {
				return true;
			}
			if (exprStat.getExpression() instanceof JCMethodInvocation) {
				JCMethodInvocation inv = (JCMethodInvocation) exprStat.getExpression();
				String methodName;
				if (inv.meth instanceof JCFieldAccess) {
					methodName = ((JCFieldAccess) inv.meth).name.toString();
				} else {
					methodName = inv.meth.toString();
				}
				if (JSweetConfig.INDEXED_GET_FUCTION_NAME.equals(methodName)
						|| JSweetConfig.INDEXED_SET_FUCTION_NAME.equals(methodName)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns the number of arguments declared by a function interface.
	 */
	public int getFunctionalTypeParameterCount(Type type) {
		String name = type.tsym.getSimpleName().toString();
		String fullName = type.toString();
		if (Runnable.class.getName().equals(fullName)) {
			return 0;
		} else if (type.toString().startsWith(JSweetConfig.FUNCTION_CLASSES_PACKAGE + ".")) {
			if (name.equals("TriFunction")) {
				return 3;
			} else if (name.equals("TriConsumer")) {
				return 3;
			} else if (name.equals("Consumer")) {
				return 1;
			} else if (name.startsWith("Function") || name.startsWith("Consumer")) {
				return Integer.parseInt(name.substring(8));
			} else {
				return -1;
			}
		} else if (type.toString().startsWith("java.util.function.")) {
			if (name.endsWith("Consumer")) {
				if (name.startsWith("Bi")) {
					return 2;
				} else {
					return 1;
				}
			} else if (name.endsWith("Function")) {
				if (name.startsWith("Bi")) {
					return 2;
				} else {
					return 1;
				}
			} else if (name.endsWith("UnaryOperator")) {
				return 1;
			} else if (name.endsWith("BinaryOperator")) {
				return 2;
			} else if (name.endsWith("Supplier")) {
				return 0;
			} else if (name.endsWith("Predicate")) {
				if (name.startsWith("Bi")) {
					return 2;
				} else {
					return 1;
				}
			} else {
				return -1;
			}
		} else {
			if (hasAnnotationType(type.tsym, JSweetConfig.ANNOTATION_FUNCTIONAL_INTERFACE)) {
				for (Element e : type.tsym.getEnclosedElements()) {
					if (e instanceof MethodSymbol) {
						return ((MethodSymbol) e).getParameters().size();
					}
				}
				return -1;
			} else {
				return -1;
			}
		}

	}

	/**
	 * Returns true if the given type symbol corresponds to a functional type
	 * (in the TypeScript way).
	 */
	public boolean isFunctionalType(TypeSymbol type) {
		String name = type.getQualifiedName().toString();
		return name.startsWith("java.util.function.") //
				|| name.equals(Runnable.class.getName()) //
				|| (type.isInterface() && (hasAnnotationType(type, FunctionalInterface.class.getName())
						|| hasAnonymousFunction(type)));
	}

	/**
	 * Tells if the given type has a anonymous function (instances can be used
	 * as lambdas).
	 */
	public boolean hasAnonymousFunction(TypeSymbol type) {
		for (Symbol s : type.getEnclosedElements()) {
			if (s instanceof MethodSymbol) {
				if (JSweetConfig.ANONYMOUS_FUNCTION_NAME.equals(s.getSimpleName().toString())) {
					return true;
				}
			}
		}
		return false;
	}

}