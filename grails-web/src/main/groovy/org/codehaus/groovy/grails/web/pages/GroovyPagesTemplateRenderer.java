package org.codehaus.groovy.grails.web.pages;

import grails.util.Environment;
import grails.util.GrailsNameUtils;
import groovy.text.Template;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang.StringUtils;
import org.codehaus.groovy.grails.commons.GrailsDomainClass;
import org.codehaus.groovy.grails.io.support.GrailsResourceUtils;
import org.codehaus.groovy.grails.web.pages.discovery.GrailsConventionGroovyPageLocator;
import org.codehaus.groovy.grails.web.pages.discovery.GroovyPageScriptSource;
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest;
import org.codehaus.groovy.grails.web.taglib.exceptions.GrailsTagException;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.ReflectionUtils;

/**
 * Service that provides the actual implementation to RenderTagLib's render tag.
 *
 * This is an internal Grails service and should not be used by plugins directly.
 * The implementation was moved from RenderTagLib, ported to Java and then refactored.
 *
 * @author Lari Hotari
 * @since 2.0
 */
public class GroovyPagesTemplateRenderer implements InitializingBean {
    private GrailsConventionGroovyPageLocator groovyPageLocator;
    private GroovyPagesTemplateEngine groovyPagesTemplateEngine;
    private ConcurrentMap<String,TemplateRendererCacheEntry> templateCache = new ConcurrentHashMap<String,TemplateRendererCacheEntry>();
    private Object scaffoldingTemplateGenerator;
    private Map<String, Collection<String>> scaffoldedActionMap;
    private Map<String, GrailsDomainClass> controllerToScaffoldedDomainClassMap;
    private Method generateViewMethod;
    private boolean reloadEnabled;
    private boolean disableCache = Environment.isDevelopmentMode();

    public void afterPropertiesSet() throws Exception {
        if (scaffoldingTemplateGenerator != null) {
            // use reflection to locate method (would cause cyclic dependency otherwise)
            generateViewMethod = ReflectionUtils.findMethod(scaffoldingTemplateGenerator.getClass(), "generateView", new Class<?>[]{GrailsDomainClass.class, String.class, Writer.class});
        }
        this.reloadEnabled = groovyPagesTemplateEngine.isReloadEnabled();
    }

    public void clearCache() {
        templateCache.clear();
    }

    public void render(GrailsWebRequest webRequest, GroovyPageBinding pageScope, Map<String, Object> attrs, Object body, Writer out) throws IOException {
        if (groovyPagesTemplateEngine==null) {
            throw new IllegalStateException("Property [groovyPagesTemplateEngine] must be set!");
        }

        String templateName=getStringValue(attrs, "template");
        if (StringUtils.isBlank(templateName)) {
            throw new GrailsTagException("Tag [render] is missing required attribute [template]");
        }

        String uri = webRequest.getAttributes().getTemplateUri(templateName, webRequest.getRequest());
        String contextPath = getStringValue(attrs, "contextPath");
        String pluginName = getStringValue(attrs, "plugin");

        Template t = findAndCacheTemplate(webRequest, pageScope, templateName,
                contextPath, pluginName, uri);

        if (t==null) {
            throw new GrailsTagException("Template not found for name [" + templateName + "] and path [" + uri + "]");
        }

        makeTemplate(t, attrs, body, out);
    }

    private Template findAndCacheTemplate(GrailsWebRequest webRequest,
            GroovyPageBinding pageScope, String templateName,
            String contextPath, String pluginName, String uri)
            throws IOException {
        String templatePath = StringUtils.isNotEmpty(contextPath) ? GrailsResourceUtils.appendPiecesForUri(contextPath, templateName) : templateName;
        GroovyPageScriptSource scriptSource = null;
        if (pluginName != null) {
            scriptSource = groovyPageLocator.findTemplateInBinding(pluginName, templatePath, pageScope);
        }  else {
            scriptSource = groovyPageLocator.findTemplateInBinding(templatePath, pageScope);
        }

        Template t = null;
        String cacheKey;
        if (scriptSource != null) {
            cacheKey = scriptSource.getURI();
        } else {
            cacheKey = contextPath + pluginName + uri;
        }

        TemplateRendererCacheEntry cacheEntry = templateCache.get(cacheKey);
        if (cacheEntry != null && cacheEntry.isValid()) {
            t = cacheEntry.template;
        } else {
            try {
                if (cacheEntry != null) {
                    // prevent several competing threads to update the template at the same time
                    cacheEntry.getLock().lock();
                    if(cacheEntry.isValid()) {
                        // another thread already updated the entry
                        t = cacheEntry.template;
                    }
                }
                if(t == null) {
                    if (scriptSource != null) {
                        t = groovyPagesTemplateEngine.createTemplate(scriptSource);
                    }
                    boolean allowCaching = !disableCache;
                    if (t==null && scaffoldingTemplateGenerator != null) {
                        t = generateScaffoldedTemplate(webRequest, uri);
                        // always enable caching for generated scaffolded template
                        allowCaching = true;
                    }
                    if (t != null && allowCaching) {
                        if (cacheEntry != null) {
                            cacheEntry.setTemplate(t);
                        } else {
                            templateCache.put(cacheKey, new TemplateRendererCacheEntry(t, reloadEnabled));
                        }
                    }
                }
            } finally {
                if (cacheEntry != null) {
                    cacheEntry.getLock().unlock();
                }
            }
        }
        return t;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void makeTemplate(Template t, Map<String, Object> attrs,
            Object body, Writer out) throws IOException {
        String var = getStringValue(attrs, "var");
        Map b = new LinkedHashMap<String, Object>();
        b.put("body", body);
        if (attrs.get("model") instanceof Map) {
            b.putAll((Map)attrs.get("model"));
        }
        if (attrs.containsKey("bean")) {
            if (StringUtils.isNotBlank(var)) {
                b.put(var, attrs.get("bean"));
            }
            else {
                b.put("it", attrs.get("bean"));
            }
        }
        if (attrs.containsKey("collection")) {
            String key = null;
            Iterator<?> iterator=InvokerHelper.asIterator(attrs.get("collection"));
            while(iterator.hasNext()) {
                Object it = iterator.next();
                if (key==null && StringUtils.isBlank(var) && it != null) {
                    key = GrailsNameUtils.getPropertyName(it.getClass());
                }
                Map itmap = new LinkedHashMap<String, Object>();
                itmap.putAll(b);
                if (StringUtils.isNotBlank(var)) {
                    itmap.put(var, it);
                }
                else {
                    itmap.put("it", it);
                    itmap.put(key, it);
                }
                t.make(itmap).writeTo(out);
            }
        } else {
            t.make(b).writeTo(out);
        }
    }

    private Template generateScaffoldedTemplate(GrailsWebRequest webRequest, String uri) throws IOException {
        Template t=null;
        Collection<String> controllerActions = scaffoldedActionMap.get(webRequest.getControllerName());
        if (controllerActions != null && controllerActions.contains(webRequest.getActionName())) {
            GrailsDomainClass domainClass = controllerToScaffoldedDomainClassMap.get(webRequest.getControllerName());
            if (domainClass != null) {
                int i = uri.lastIndexOf('/');
                String scaffoldedtemplateName = i > -1 ? uri.substring(i) : uri;
                if (scaffoldedtemplateName.toLowerCase().endsWith(".gsp")) {
                    scaffoldedtemplateName = scaffoldedtemplateName.substring(0, scaffoldedtemplateName.length()-4);
                }
                FastStringWriter sw = new FastStringWriter();
                ReflectionUtils.invokeMethod(generateViewMethod, scaffoldingTemplateGenerator, domainClass, scaffoldedtemplateName, sw);
                t = groovyPagesTemplateEngine.createTemplate(sw.toString(), uri);
            }
        }
        return t;
    }

    private static class TemplateRendererCacheEntry {
        private long timestamp=System.currentTimeMillis();
        private Template template;
        private boolean reloadEnabled;
        private final Lock lock = new ReentrantLock();

        public TemplateRendererCacheEntry(Template t, boolean reloadEnabled) {
            this.template = t;
            this.reloadEnabled = reloadEnabled;
        }

        public Lock getLock() {
            return lock;
        }

        public boolean isValid() {
            return !reloadEnabled || (System.currentTimeMillis() - timestamp < GroovyPageMetaInfo.LASTMODIFIED_CHECK_INTERVAL);
        }

        public void setTemplate(Template template) {
            this.template = template;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private String getStringValue(Map<String, Object> attrs, String key) {
        Object val=attrs.get(key);
        if (val==null) return "";
        if (val instanceof String) {
            return (String)val;
        }
        return String.valueOf(val);
    }

    public void setGroovyPageLocator(GrailsConventionGroovyPageLocator locator) {
        groovyPageLocator = locator;
    }

    public void setGroovyPagesTemplateEngine(GroovyPagesTemplateEngine engine) {
        groovyPagesTemplateEngine = engine;
    }

    public void setScaffoldingTemplateGenerator(Object generator) {
        scaffoldingTemplateGenerator = generator;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void setScaffoldedActionMap(Map map) {
        scaffoldedActionMap = map;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void setControllerToScaffoldedDomainClassMap(Map map) {
        controllerToScaffoldedDomainClassMap = map;
    }
}
