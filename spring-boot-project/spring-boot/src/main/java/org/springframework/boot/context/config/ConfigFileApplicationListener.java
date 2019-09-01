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

package org.springframework.boot.context.config;

import org.apache.commons.logging.Log;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.cloud.CloudFoundryVcapEnvironmentPostProcessor;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.env.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.boot.env.SystemEnvironmentPropertySourceEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * {@link EnvironmentPostProcessor} that configures the context environment by loading
 * properties from well known file locations. By default properties will be loaded from
 * 'application.properties' and/or 'application.yml' files in the following locations:
 * <ul>
 * <li>file:./config/:</li>
 * <li>file:./</li>
 * <li>classpath:config/</li>
 * <li>classpath:</li>
 * </ul>
 * The list is ordered by precedence (properties defined in locations higher in the list
 * override those defined in lower locations).
 * <p>
 * Alternative search locations and names can be specified using
 * {@link #setSearchLocations(String)} and {@link #setSearchNames(String)}.
 * <p>
 * Additional files will also be loaded based on active profiles. For example if a 'web'
 * profile is active 'application-web.properties' and 'application-web.yml' will be
 * considered.
 * <p>
 * The 'spring.config.name' property can be used to specify an alternative name to load
 * and the 'spring.config.location' property can be used to specify alternative search
 * locations or specific files.
 * <p>
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Eddú Meléndez
 * @author Madhura Bhave
 */
public class ConfigFileApplicationListener implements EnvironmentPostProcessor, SmartApplicationListener, Ordered {

	private static final String DEFAULT_PROPERTIES = "defaultProperties";

	// Note the order is from least to most specific (last one wins)
	private static final String DEFAULT_SEARCH_LOCATIONS = "classpath:/,classpath:/config/,file:./,file:./config/";

	private static final String DEFAULT_NAMES = "application";

	private static final Set<String> NO_SEARCH_NAMES = Collections.singleton(null);

	private static final Bindable<String[]> STRING_ARRAY = Bindable.of(String[].class);

	/**
	 * The "active profiles" property name.
	 */
	public static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";

	/**
	 * The "includes profiles" property name.
	 */
	public static final String INCLUDE_PROFILES_PROPERTY = "spring.profiles.include";

	/**
	 * The "config name" property name.
	 */
	public static final String CONFIG_NAME_PROPERTY = "spring.config.name";

	/**
	 * The "config location" property name.
	 */
	public static final String CONFIG_LOCATION_PROPERTY = "spring.config.location";

	/**
	 * The "config additional location" property name.
	 */
	public static final String CONFIG_ADDITIONAL_LOCATION_PROPERTY = "spring.config.additional-location";

	/**
	 * The default order for the processor.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	private final DeferredLog logger = new DeferredLog();

	private String searchLocations;

	private String names;

	private int order = DEFAULT_ORDER;

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType)
				|| ApplicationPreparedEvent.class.isAssignableFrom(eventType);
	}

	// 初始化 environment 入口
	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof ApplicationEnvironmentPreparedEvent) {
			onApplicationEnvironmentPreparedEvent((ApplicationEnvironmentPreparedEvent) event);
		}
		if (event instanceof ApplicationPreparedEvent) {
			onApplicationPreparedEvent(event);
		}
	}

	/**
	 * 初始化 environment
	 * @see SystemEnvironmentPropertySourceEnvironmentPostProcessor
	 * @see SpringApplicationJsonEnvironmentPostProcessor
	 * @see CloudFoundryVcapEnvironmentPostProcessor
	 * @see ConfigFileApplicationListener
     */
	private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) {
		// 读取 EnvironmentPostProcessor
		List<EnvironmentPostProcessor> postProcessors = loadPostProcessors();
		postProcessors.add(this);
		// 排序 EnvironmentPostProcessor
		AnnotationAwareOrderComparator.sort(postProcessors);
		// postProcessEnvironment
		for (EnvironmentPostProcessor postProcessor : postProcessors) {
			postProcessor.postProcessEnvironment(event.getEnvironment(), event.getSpringApplication());
		}
	}

	// 读取 EnvironmentPostProcessor
	List<EnvironmentPostProcessor> loadPostProcessors() {
		return SpringFactoriesLoader.loadFactories(EnvironmentPostProcessor.class, getClass().getClassLoader());
	}

	// 读取 profile 和其对应的配置并设置激活的profile
	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		addPropertySources(environment, application.getResourceLoader());
	}

	// 注册 PropertySourceOrderingPostProcessor
	private void onApplicationPreparedEvent(ApplicationEvent event) {
		this.logger.switchTo(ConfigFileApplicationListener.class);
		addPostProcessors(((ApplicationPreparedEvent) event).getApplicationContext());
	}

	/**
	 * 将 randomPropertySource 加入 environment
	 * 读取 profile 和其对应的配置并设置激活的profile
	 */
	protected void addPropertySources(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
		RandomValuePropertySource.addToEnvironment(environment);
		new Loader(environment, resourceLoader).load();
	}

	protected void addPostProcessors(ConfigurableApplicationContext context) {
		context.addBeanFactoryPostProcessor(new PropertySourceOrderingPostProcessor(context));
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Set the search locations that will be considered as a comma-separated list. Each
	 * search location should be a directory path (ending in "/") and it will be prefixed
	 * by the file names constructed from {@link #setSearchNames(String) search names} and
	 * profiles (if any) plus file extensions supported by the properties loaders.
	 * Locations are considered in the order specified, with later items taking precedence
	 * (like a map merge).
	 * @param locations the search locations
	 */
	public void setSearchLocations(String locations) {
		Assert.hasLength(locations, "Locations must not be empty");
		this.searchLocations = locations;
	}

	/**
	 * Sets the names of the files that should be loaded (excluding file extension) as a
	 * comma-separated list.
	 * @param names the names to load
	 */
	public void setSearchNames(String names) {
		Assert.hasLength(names, "Names must not be empty");
		this.names = names;
	}

	/**
	 * 读取 profile 和其对应的配置并设置激活的profile
	 */
	private class PropertySourceOrderingPostProcessor implements BeanFactoryPostProcessor, Ordered {

		private ConfigurableApplicationContext context;

		PropertySourceOrderingPostProcessor(ConfigurableApplicationContext context) {
			this.context = context;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
			reorderSources(this.context.getEnvironment());
		}

		private void reorderSources(ConfigurableEnvironment environment) {
			PropertySource<?> defaultProperties = environment.getPropertySources().remove(DEFAULT_PROPERTIES);
			if (defaultProperties != null) {
				environment.getPropertySources().addLast(defaultProperties);
			}
		}

	}

	/**
	 * 读取 profile 和其对应的配置并设置激活的profile
	 */
	private class Loader {

		private final Log logger = ConfigFileApplicationListener.this.logger;

		private final ConfigurableEnvironment environment;

		private final PropertySourcesPlaceholdersResolver placeholdersResolver;

		private final ResourceLoader resourceLoader;

		private final List<PropertySourceLoader> propertySourceLoaders;
		// 待处理的 profile
		private Deque<Profile> waitForProcessedProfiles;
		// 已处理的 profile
		private List<Profile> processedProfiles;
		// 是否存在激活的 profile
		private boolean activatedProfiles;
		// 所有已加载的配置
		private Map<Profile, MutablePropertySources> loaded;
		// 已加载的配置缓存
		private Map<DocumentsCacheKey, List<Document>> loadDocumentsCache = new HashMap<>();

		Loader(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
			this.environment = environment;
			this.placeholdersResolver = new PropertySourcesPlaceholdersResolver(this.environment);
			this.resourceLoader = (resourceLoader != null) ? resourceLoader : new DefaultResourceLoader();
			this.propertySourceLoaders = SpringFactoriesLoader.loadFactories(PropertySourceLoader.class, getClass().getClassLoader());
		}

		public void load() {
			this.waitForProcessedProfiles = new LinkedList<>();
			this.processedProfiles = new LinkedList<>();
			this.activatedProfiles = false;
			this.loaded = new LinkedHashMap<>();
			// 初始化 waitForProcessedProfiles
			initializeProfiles();
			// 迭代 waitForProcessedProfiles 并加载相应的配置
			while (!this.waitForProcessedProfiles.isEmpty()) {
				Profile profile = this.waitForProcessedProfiles.poll();
				// 如果 profile 不是 default, 则将其加入 environment
				if (profile != null && !profile.isDefaultProfile()) {
					addProfileToEnvironment(profile.getName());
				}
				// 加载 profile 对应的配置
				load(profile, this::getPositiveProfileFilter, addToLoaded(MutablePropertySources::addLast, false));
				this.processedProfiles.add(profile);
			}
			// 设置 activeProfiles
			resetEnvironmentProfiles(this.processedProfiles);
			// 再次加载 default profile
			load(null, this::getNegativeProfileFilter, addToLoaded(MutablePropertySources::addFirst, true));
			// 将所有已加载的配置放入 environment
			addLoadedPropertySources();
		}

		/**
		 * 初始化 waitForProcessedProfiles
		 */
		private void initializeProfiles() {
			// 将 default as null 加入列表
			this.waitForProcessedProfiles.add(null);
			// 获取已激活的 profile
			Set<Profile> activatedViaProperty = getProfilesActivatedViaProperty();
			// 读取非 activatedViaProperty 的 profile
			this.waitForProcessedProfiles.addAll(getOtherActiveProfiles(activatedViaProperty));
			addActiveProfiles(activatedViaProperty);
			// 如果只有一个 profile, 则添加 default profile
			// what the fuck for this method's first line ???
			if (this.waitForProcessedProfiles.size() == 1) {
				for (String defaultProfileName : this.environment.getDefaultProfiles()) {
					Profile defaultProfile = new Profile(defaultProfileName, true);
					this.waitForProcessedProfiles.add(defaultProfile);
				}
			}
		}

		// 读取 spring.profiles.active、spring.profiles.include
		private Set<Profile> getProfilesActivatedViaProperty() {
			if (!this.environment.containsProperty(ACTIVE_PROFILES_PROPERTY)
					&& !this.environment.containsProperty(INCLUDE_PROFILES_PROPERTY)) {
				return Collections.emptySet();
			}
			Binder binder = Binder.get(this.environment);
			Set<Profile> activeProfiles = new LinkedHashSet<>();
			activeProfiles.addAll(getProfiles(binder, INCLUDE_PROFILES_PROPERTY));
			activeProfiles.addAll(getProfiles(binder, ACTIVE_PROFILES_PROPERTY));
			return activeProfiles;
		}

		// 从 environment.activeProfiles 读取非 activatedViaProperty 的 profile
		private List<Profile> getOtherActiveProfiles(Set<Profile> activatedViaProperty) {
			return Arrays.stream(this.environment.getActiveProfiles())
					.map(Profile::new)
					.filter((profile) -> !activatedViaProperty.contains(profile))
					.collect(Collectors.toList());
		}

		// 将 activeProfiles 加入 loader
		void addActiveProfiles(Set<Profile> profiles) {
			// 如果 profiles 为空, 则结束
			if (profiles.isEmpty()) {
				return;
			}
			// 如果已存在激活的 profile, 则结束
			if (this.activatedProfiles) {
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Profiles already activated, '" + profiles + "' will not be applied");
				}
				return;
			}
			// 将 profiles 加入 waitForProcessedProfiles
			this.waitForProcessedProfiles.addAll(profiles);
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Activated activeProfiles " + StringUtils.collectionToCommaDelimitedString(profiles));
			}
			// 标记 activatedProfiles
			this.activatedProfiles = true;
			// 移除 default profile
			removeUnprocessedDefaultProfiles();
		}

		private void removeUnprocessedDefaultProfiles() {
			this.waitForProcessedProfiles.removeIf((profile) -> (profile != null && profile.isDefaultProfile()));
		}

		private DocumentFilter getPositiveProfileFilter(Profile profile) {
			return (Document document) -> {
				if (profile == null) {
					return ObjectUtils.isEmpty(document.getProfiles());
				}
				return ObjectUtils.containsElement(document.getProfiles(), profile.getName())
						&& this.environment.acceptsProfiles(Profiles.of(document.getProfiles()));
			};
		}

		private DocumentFilter getNegativeProfileFilter(Profile profile) {
			return (Document document) -> (profile == null && !ObjectUtils.isEmpty(document.getProfiles())
					&& this.environment.acceptsProfiles(Profiles.of(document.getProfiles())));
		}

		private DocumentConsumer addToLoaded(BiConsumer<MutablePropertySources, PropertySource<?>> addMethod,
				boolean checkForExisting) {
			return (profile, document) -> {
				if (checkForExisting) {
					// 如果已存在同名 propertySource, 则结束
					for (MutablePropertySources merged : this.loaded.values()) {
						if (merged.contains(document.getPropertySource().getName())) {
							return;
						}
					}
				}
				// 将 profile 和 document 加入 loaded
				MutablePropertySources merged = this.loaded.computeIfAbsent(profile, (k) -> new MutablePropertySources());
				// 合并 propertySource
				addMethod.accept(merged, document.getPropertySource());
			};
		}

		// 加载 profile 对应的配置
		private void load(Profile profile, DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			// 获取搜索路径
			Set<String> locations = getSearchLocations();
			// 迭代路径并加载配置
			locations.forEach((location) -> {
				// 读取配置文件名称
				boolean isFolder = location.endsWith("/");
				Set<String> names = isFolder ? getSearchNames() : NO_SEARCH_NAMES;
				// 在当前路径上加载以上文件名称的配置
				names.forEach((name) -> load(location, name, profile, filterFactory, consumer));
			});
		}

		// 在当前路径上加载指定文件名称的配置
		private void load(String location, String name, Profile profile, DocumentFilterFactory filterFactory,
				DocumentConsumer consumer) {
			// 如果指定名称不存在, 则说明 location 是具体的文件路径
			if (!StringUtils.hasText(name)) {
				// 迭代 propertySourceLoaders 并加载配置文件
				for (PropertySourceLoader loader : this.propertySourceLoaders) {
					if (canLoadFileExtension(loader, location)) {
						// 加载配置
						load(loader, location, profile, filterFactory.getDocumentFilter(profile), consumer);
						return;
					}
				}
			}
			// 反之, 则拼接出具体的文件路径并加载之
			Set<String> processed = new HashSet<>();
			for (PropertySourceLoader loader : this.propertySourceLoaders) {
				for (String fileExtension : loader.getFileExtensions()) {
					if (processed.add(fileExtension)) {
						// 合并 location、name 并作为文件前缀
						// 将文件前缀、文件后缀作为参数传递下去以便拼接具体的文件路径
						loadForFileExtension(loader, location + name, "." + fileExtension, profile, filterFactory, consumer);
					}
				}
			}
		}

		private boolean canLoadFileExtension(PropertySourceLoader loader, String name) {
			return Arrays.stream(loader.getFileExtensions())
					.anyMatch((fileExtension) -> StringUtils.endsWithIgnoreCase(name, fileExtension));
		}

		// 拼接文件路径并加载配置
		private void loadForFileExtension(PropertySourceLoader loader, String prefix, String fileExtension,
				Profile profile, DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			// 创建 DocumentFilter
			DocumentFilter defaultFilter = filterFactory.getDocumentFilter(null);
			DocumentFilter profileFilter = filterFactory.getDocumentFilter(profile);
			if (profile != null) {
				// 拼接出具体的文件路径
				// like-this application-dev.yml
				String profileSpecificFile = prefix + "-" + profile + fileExtension;
				// 加载配置文件
				load(loader, profileSpecificFile, profile, defaultFilter, consumer);
				load(loader, profileSpecificFile, profile, profileFilter, consumer);
				// 再次加载已处理的配置文件 ???
				for (Profile processedProfile : this.processedProfiles) {
					if (processedProfile != null) {
						String previouslyLoaded = prefix + "-" + processedProfile + fileExtension;
						load(loader, previouslyLoaded, profile, profileFilter, consumer);
					}
				}
			}
			// 加载缺省的文件路径配置
			// like-this application.yml
			load(loader, prefix + fileExtension, profile, profileFilter, consumer);
		}

		// 加载指定文件的配置文件
		private void load(PropertySourceLoader loader, String location, Profile profile, DocumentFilter filter,
				DocumentConsumer consumer) {
			try {
				// 加载指定位置的资源
				Resource resource = this.resourceLoader.getResource(location);
				// 如果资源不存在, 则结束
				if (resource == null || !resource.exists()) {
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription("Skipped missing config ", location, resource,
								profile);
						this.logger.trace(description);
					}
					return;
				}
				// 如果资源不存在扩展名, 则结束
				if (!StringUtils.hasText(StringUtils.getFilenameExtension(resource.getFilename()))) {
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription("Skipped empty config extension ", location,
								resource, profile);
						this.logger.trace(description);
					}
					return;
				}
				// 读取资源并转换成 Document
				String name = "applicationConfig: [" + location + "]";
				List<Document> documents = loadDocuments(loader, name, resource);
				// 如果 documents 为空, 则结束
				if (CollectionUtils.isEmpty(documents)) {
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription("Skipped unloaded config ", location, resource,
								profile);
						this.logger.trace(description);
					}
					return;
				}
				/**
				 * 迭代 documents, 如果满足 DocumentFilter, 则将 Document 加入 loaded 结果集
				 * @see #getPositiveProfileFilter
				 * @see #getNegativeProfileFilter
				 */
				List<Document> loaded = new ArrayList<>();
				for (Document document : documents) {
					if (filter.match(document)) {
						// 将 activeProfiles 加入 loader
						addActiveProfiles(document.getActiveProfiles());
						// 将 includeProfiles 加入 loader
						addIncludedProfiles(document.getIncludeProfiles());
						// 将 Document 加入 loaded 结果集
						loaded.add(document);
					}
				}
				/**
				 * 迭代 loaded 并将其加入 loader.loaded
				 * @see #addToLoaded(BiConsumer, boolean)
				 */
				Collections.reverse(loaded);
				if (!loaded.isEmpty()) {
					loaded.forEach((document) -> consumer.accept(profile, document));
					// 打印日志
					if (this.logger.isDebugEnabled()) {
						StringBuilder description = getDescription("Loaded config file ", location, resource, profile);
						this.logger.debug(description);
					}
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to load property " + "source from location '" + location + "'",
						ex);
			}
		}

		// 将 includeProfiles 加入 loader
		private void addIncludedProfiles(Set<Profile> includeProfiles) {
			LinkedList<Profile> existingProfiles = new LinkedList<>(this.waitForProcessedProfiles);
			this.waitForProcessedProfiles.clear();
			this.waitForProcessedProfiles.addAll(includeProfiles);
			this.waitForProcessedProfiles.removeAll(this.processedProfiles);
			this.waitForProcessedProfiles.addAll(existingProfiles);
		}

		// 读取资源并转换成 Document
		private List<Document> loadDocuments(PropertySourceLoader loader, String name, Resource resource)
				throws IOException {
			// 构建缓存 key
			DocumentsCacheKey cacheKey = new DocumentsCacheKey(loader, resource);
			// 从缓存读取
			List<Document> documents = this.loadDocumentsCache.get(cacheKey);
			if (documents == null) {
				// 读取资源并转换成 Document
				List<PropertySource<?>> loaded = loader.load(name, resource);
				documents = asDocuments(loaded);
				// 缓存 Document
				this.loadDocumentsCache.put(cacheKey, documents);
			}
			return documents;
		}

		// 将 PropertySource 转成 Document
		private List<Document> asDocuments(List<PropertySource<?>> loaded) {
			if (loaded == null) {
				return Collections.emptyList();
			}
			return loaded.stream()
					.map((propertySource) -> {
						Binder binder = new Binder(ConfigurationPropertySources.from(propertySource), this.placeholdersResolver);
						// 读取 spring.profiles
						String[] profiles = binder.bind("spring.profiles", STRING_ARRAY).orElse(null);
						// 读取 spring.profiles.active
						Set<Profile> activeProfiles = getProfiles(binder, ACTIVE_PROFILES_PROPERTY);
						// 读取 spring.profiles.include
						Set<Profile> includeProfiles = getProfiles(binder, INCLUDE_PROFILES_PROPERTY);
						return new Document(propertySource, profiles, activeProfiles, includeProfiles);
					})
					.collect(Collectors.toList());
		}

		private StringBuilder getDescription(String prefix, String location, Resource resource, Profile profile) {
			StringBuilder result = new StringBuilder(prefix);
			try {
				if (resource != null) {
					String uri = resource.getURI().toASCIIString();
					result.append("'");
					result.append(uri);
					result.append("' (");
					result.append(location);
					result.append(")");
				}
			}
			catch (IOException ex) {
				result.append(location);
			}
			if (profile != null) {
				result.append(" for profile ");
				result.append(profile);
			}
			return result;
		}

		private Set<Profile> getProfiles(Binder binder, String name) {
			return binder.bind(name, STRING_ARRAY).map(this::asProfileSet).orElse(Collections.emptySet());
		}

		private Set<Profile> asProfileSet(String[] profileNames) {
			List<Profile> profiles = new ArrayList<>();
			for (String profileName : profileNames) {
				profiles.add(new Profile(profileName));
			}
			return new LinkedHashSet<>(profiles);
		}

		// 将 profile 加入 environment.activeProfiles
		private void addProfileToEnvironment(String profile) {
			for (String activeProfile : this.environment.getActiveProfiles()) {
				if (activeProfile.equals(profile)) {
					return;
				}
			}
			this.environment.addActiveProfile(profile);
		}

		// 获取搜索路径
		private Set<String> getSearchLocations() {
			// 读取 spring.config.location
			if (this.environment.containsProperty(CONFIG_LOCATION_PROPERTY)) {
				return getSearchLocations(CONFIG_LOCATION_PROPERTY);
			}
			// 读取 spring.config.additional-location
			Set<String> locations = getSearchLocations(CONFIG_ADDITIONAL_LOCATION_PROPERTY);
			// 读取缺省路径
			Set<String> defaultLocations = asResolvedSet(ConfigFileApplicationListener.this.searchLocations, DEFAULT_SEARCH_LOCATIONS);
			// 合并 additional-location 与缺省路径
			locations.addAll(defaultLocations);
			return locations;
		}

		private Set<String> getSearchLocations(String propertyName) {
			Set<String> locations = new LinkedHashSet<>();
			if (this.environment.containsProperty(propertyName)) {
				for (String path : asResolvedSet(this.environment.getProperty(propertyName), null)) {
					if (!path.contains("$")) {
						path = StringUtils.cleanPath(path);
						if (!ResourceUtils.isUrl(path)) {
							path = ResourceUtils.FILE_URL_PREFIX + path;
						}
					}
					locations.add(path);
				}
			}
			return locations;
		}

		// 读取配置文件的名称
		private Set<String> getSearchNames() {
			// 读取 spring.config.name
			if (this.environment.containsProperty(CONFIG_NAME_PROPERTY)) {
				String property = this.environment.getProperty(CONFIG_NAME_PROPERTY);
				return asResolvedSet(property, null);
			}
			// 读取缺省的配置文件名称(application)
			return asResolvedSet(ConfigFileApplicationListener.this.names, DEFAULT_NAMES);
		}

		private Set<String> asResolvedSet(String value, String fallback) {
			List<String> list = Arrays.asList(StringUtils.trimArrayElements(StringUtils.commaDelimitedListToStringArray(
					(value != null) ? this.environment.resolvePlaceholders(value) : fallback)));
			Collections.reverse(list);
			return new LinkedHashSet<>(list);
		}

		/**
		 * 设置 activeProfiles
		 */
		private void resetEnvironmentProfiles(List<Profile> processedProfiles) {
			String[] names = processedProfiles.stream()
					.filter((profile) -> profile != null && !profile.isDefaultProfile())
					.map(Profile::getName)
					.toArray(String[]::new);
			this.environment.setActiveProfiles(names);
		}

		// 将所有已加载的配置放入 environment
		private void addLoadedPropertySources() {
			MutablePropertySources destination = this.environment.getPropertySources();
			List<MutablePropertySources> loaded = new ArrayList<>(this.loaded.values());
			Collections.reverse(loaded);
			String lastAdded = null;
			Set<String> added = new HashSet<>();
			// 迭代 loaded 并将其加入 environment
			for (MutablePropertySources sources : loaded) {
				for (PropertySource<?> source : sources) {
					if (added.add(source.getName())) {
						addLoadedPropertySource(destination, lastAdded, source);
						lastAdded = source.getName();
					}
				}
			}
		}

		private void addLoadedPropertySource(MutablePropertySources destination, String lastAdded,
				PropertySource<?> source) {
			if (lastAdded == null) {
				if (destination.contains(DEFAULT_PROPERTIES)) {
					destination.addBefore(DEFAULT_PROPERTIES, source);
				}
				else {
					destination.addLast(source);
				}
			}
			else {
				destination.addAfter(lastAdded, source);
			}
		}

	}

	/**
	 * A Spring Profile that can be loaded.
	 */
	private static class Profile {

		private final String name;

		private final boolean defaultProfile;

		Profile(String name) {
			this(name, false);
		}

		Profile(String name, boolean defaultProfile) {
			Assert.notNull(name, "Name must not be null");
			this.name = name;
			this.defaultProfile = defaultProfile;
		}

		public String getName() {
			return this.name;
		}

		public boolean isDefaultProfile() {
			return this.defaultProfile;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			if (obj == null || obj.getClass() != getClass()) {
				return false;
			}
			return ((Profile) obj).name.equals(this.name);
		}

		@Override
		public int hashCode() {
			return this.name.hashCode();
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * Cache key used to save loading the same document multiple times.
	 */
	private static class DocumentsCacheKey {

		private final PropertySourceLoader loader;

		private final Resource resource;

		DocumentsCacheKey(PropertySourceLoader loader, Resource resource) {
			this.loader = loader;
			this.resource = resource;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			DocumentsCacheKey other = (DocumentsCacheKey) obj;
			return this.loader.equals(other.loader) && this.resource.equals(other.resource);
		}

		@Override
		public int hashCode() {
			return this.loader.hashCode() * 31 + this.resource.hashCode();
		}

	}

	/**
	 * A single document loaded by a {@link PropertySourceLoader}.
	 */
	private static class Document {

		private final PropertySource<?> propertySource;

		private String[] profiles;

		private final Set<Profile> activeProfiles;

		private final Set<Profile> includeProfiles;

		Document(PropertySource<?> propertySource, String[] profiles, Set<Profile> activeProfiles,
				Set<Profile> includeProfiles) {
			this.propertySource = propertySource;
			this.profiles = profiles;
			this.activeProfiles = activeProfiles;
			this.includeProfiles = includeProfiles;
		}

		public PropertySource<?> getPropertySource() {
			return this.propertySource;
		}

		public String[] getProfiles() {
			return this.profiles;
		}

		public Set<Profile> getActiveProfiles() {
			return this.activeProfiles;
		}

		public Set<Profile> getIncludeProfiles() {
			return this.includeProfiles;
		}

		@Override
		public String toString() {
			return this.propertySource.toString();
		}

	}

	/**
	 * Factory used to create a {@link DocumentFilter}.
	 */
	@FunctionalInterface
	private interface DocumentFilterFactory {

		/**
		 * Create a filter for the given profile.
		 * @param profile the profile or {@code null}
		 * @return the filter
		 */
		DocumentFilter getDocumentFilter(Profile profile);

	}

	/**
	 * Filter used to restrict when a {@link Document} is loaded.
	 */
	@FunctionalInterface
	private interface DocumentFilter {

		boolean match(Document document);

	}

	/**
	 * Consumer used to handle a loaded {@link Document}.
	 */
	@FunctionalInterface
	private interface DocumentConsumer {

		void accept(Profile profile, Document document);

	}

}
