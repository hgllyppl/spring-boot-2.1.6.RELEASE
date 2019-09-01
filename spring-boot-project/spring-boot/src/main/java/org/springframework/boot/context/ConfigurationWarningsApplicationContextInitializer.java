/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ApplicationContextInitializer} to report warnings for common misconfiguration
 * mistakes.
 *
 * @author Phillip Webb
 * @since 1.2.0
 */
public class ConfigurationWarningsApplicationContextInitializer
		implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final Log logger = LogFactory.getLog(ConfigurationWarningsApplicationContextInitializer.class);

	// 注册 ConfigurationWarningsPostProcessor
	@Override
	public void initialize(ConfigurableApplicationContext context) {
		context.addBeanFactoryPostProcessor(new ConfigurationWarningsPostProcessor(getChecks()));
	}

	/**
	 * Returns the checks that should be applied.
	 * @return the checks to apply
	 */
	protected Check[] getChecks() {
		return new Check[] { new ComponentScanPackageCheck() };
	}

	/**
	 * 检查扫描路径是否同 org/org.springframework 一样
	 * 如果一样, 将打印警告日志
	 *
	 * 此场景可能导致应用无法正常启动
	 */
	protected static final class ConfigurationWarningsPostProcessor
			implements PriorityOrdered, BeanDefinitionRegistryPostProcessor {

		private Check[] checks;

		public ConfigurationWarningsPostProcessor(Check[] checks) {
			this.checks = checks;
		}

		@Override
		public int getOrder() {
			return Ordered.LOWEST_PRECEDENCE - 1;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		}

		// 检查扫描路径并打印警告日志
		@Override
		public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
			for (Check check : this.checks) {
				// 检查扫描路径
				String message = check.getWarning(registry);
				// 打印警告日志
				if (StringUtils.hasLength(message)) {
					warn(message);
				}
			}

		}

		private void warn(String message) {
			if (logger.isWarnEnabled()) {
				logger.warn(String.format("%n%n** WARNING ** : %s%n%n", message));
			}
		}

	}

	@FunctionalInterface
	protected interface Check {

		String getWarning(BeanDefinitionRegistry registry);

	}

	/**
	 * 检查扫描路径是否存在问题
	 */
	protected static class ComponentScanPackageCheck implements Check {

		private static final Set<String> PROBLEM_PACKAGES;

		static {
			Set<String> packages = new HashSet<>();
			packages.add("org.springframework");
			packages.add("org");
			PROBLEM_PACKAGES = Collections.unmodifiableSet(packages);
		}

		@Override
		public String getWarning(BeanDefinitionRegistry registry) {
			// 读取扫描路径
			Set<String> scannedPackages = getComponentScanningPackages(registry);
			// 检查扫描路径
			List<String> problematicPackages = getProblematicPackages(scannedPackages);
			if (problematicPackages.isEmpty()) {
				return null;
			}
			// 返回错误信息
			return "Your ApplicationContext is unlikely to " + "start due to a @ComponentScan of "
					+ StringUtils.collectionToDelimitedString(problematicPackages, ", ") + ".";
		}

		// 遍历 BeanDefinitionNames, 如果 beanDef 带有 ComponentScan
		// 则读取其扫描路径
		protected Set<String> getComponentScanningPackages(BeanDefinitionRegistry registry) {
			Set<String> packages = new LinkedHashSet<>();
			String[] names = registry.getBeanDefinitionNames();
			for (String name : names) {
				BeanDefinition definition = registry.getBeanDefinition(name);
				if (definition instanceof AnnotatedBeanDefinition) {
					AnnotatedBeanDefinition annotatedDefinition = (AnnotatedBeanDefinition) definition;
					addComponentScanningPackages(packages, annotatedDefinition.getMetadata());
				}
			}
			return packages;
		}

		// 读取 ComponentScan
		private void addComponentScanningPackages(Set<String> packages, AnnotationMetadata metadata) {
			AnnotationAttributes attributes = AnnotationAttributes
					.fromMap(metadata.getAnnotationAttributes(ComponentScan.class.getName(), true));
			if (attributes != null) {
				addPackages(packages, attributes.getStringArray("value"));
				addPackages(packages, attributes.getStringArray("basePackages"));
				addClasses(packages, attributes.getStringArray("basePackageClasses"));
				if (packages.isEmpty()) {
					packages.add(ClassUtils.getPackageName(metadata.getClassName()));
				}
			}
		}

		private void addPackages(Set<String> packages, String[] values) {
			if (values != null) {
				Collections.addAll(packages, values);
			}
		}

		private void addClasses(Set<String> packages, String[] values) {
			if (values != null) {
				for (String value : values) {
					packages.add(ClassUtils.getPackageName(value));
				}
			}
		}

		// 检查扫描路径
		private List<String> getProblematicPackages(Set<String> scannedPackages) {
			List<String> problematicPackages = new ArrayList<>();
			for (String scannedPackage : scannedPackages) {
				if (isProblematicPackage(scannedPackage)) {
					problematicPackages.add(getDisplayName(scannedPackage));
				}
			}
			return problematicPackages;
		}

		private boolean isProblematicPackage(String scannedPackage) {
			if (scannedPackage == null || scannedPackage.isEmpty()) {
				return true;
			}
			return PROBLEM_PACKAGES.contains(scannedPackage);
		}

		private String getDisplayName(String scannedPackage) {
			if (scannedPackage == null || scannedPackage.isEmpty()) {
				return "the default package";
			}
			return "'" + scannedPackage + "'";
		}

	}

}
