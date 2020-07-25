package swagger.grails4.openapi

import grails.core.GrailsApplication
import grails.core.GrailsControllerClass
import grails.validation.Validateable
import grails.web.Action
import grails.web.mapping.UrlCreator
import grails.web.mapping.UrlMappingsHolder
import groovy.util.logging.Slf4j
import io.swagger.v3.oas.integration.api.OpenAPIConfiguration
import io.swagger.v3.oas.integration.api.OpenApiReader
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.tags.Tag
import swagger.grails4.openapi.builder.AnnotationBuilder
import swagger.grails4.openapi.builder.OperationBuilder
import swagger.grails4.openapi.builder.TagBuilder

import java.lang.reflect.Method

/**
 * Groovy annotation reader for OpenAPI
 *
 * @author bo.yang <bo.yang@telecwin.com>
 */
@Slf4j
class Reader implements OpenApiReader {

    final static String DEFAULT_MIME = "*/*"
    final static String JSON_MIME = "application/json"

    OpenAPIConfiguration config
    GrailsApplication application

    private OpenAPI openAPI = new OpenAPI()

    @Override
    void setConfiguration(OpenAPIConfiguration openApiConfiguration) {
        this.config = openApiConfiguration
    }

    /**
     * Read controller classes, build OpenAPI object.
     *
     * @param classes controller classes or any classes with @ApiDoc annotation
     * @param resources TODO Understanding what it is
     * @return openAPI object
     */
    @Override
    OpenAPI read(Set<Class<?>> classes, Map<String, Object> resources) {
        classes.each {
            processApiDocAnnotation(it)
        }
        // sort controller by tag name
        openAPI.tags = openAPI.tags.sort { it.name }
        openAPI
    }

    def processApiDocAnnotation(Class controllerClass) {
        log.debug("Scanning class: ${controllerClass.simpleName}")
        // get all controller grails artifacts
        def allControllerArtifacts = application.getArtefacts("Controller")
        // find controller artifact with the same controller class
        GrailsControllerClass controllerArtifact = allControllerArtifacts.find { it.clazz == controllerClass } as GrailsControllerClass
        if (!controllerArtifact) {
            log.error("No grails controller found for class ${controllerClass}")
            return
        }
        def applicationContext = application.mainContext
        def urlMappingsHolder = applicationContext.getBean("grailsUrlMappingsHolder", UrlMappingsHolder)
        urlMappingsHolder.urlMappings.each {
            log.debug("url mapping: ${it}")
        }
        if (!openAPI.paths) {
            openAPI.paths(new Paths())
        }
        // Add global tags
        Tag controllerTag = buildControllerDoc(controllerArtifact)

        // iterate actions only
        controllerArtifact.actions.each { String actionName ->
            log.debug("Scanning action: ${actionName}")
            // get java reflection method object
            Method method = controllerClass.methods.find { it.name == actionName }
            if (!method) {
                log.error("No method found for action '${actionName}'!")
                return
            }
            def apiDoc = method.getAnnotation(ApiDoc)
            if (!apiDoc) {
                return
            }
            // process operation closure
            def closureClass = apiDoc.operation()
            def operationBuilder = new OperationBuilder(reader: this)
            // resolve grails action command parameters
            operationBuilder.model.requestBody = buildActionCommandParameters(actionName, controllerArtifact)
            // process operation closure that can override parameters information
            def operation = processClosure(closureClass, operationBuilder) as Operation
            operation.addTagsItem(controllerTag.name)
            buildPathItem(operation, actionName, controllerArtifact, urlMappingsHolder)
        }
    }

    def buildPathItem(Operation operation, String actionName, GrailsControllerClass controllerArtifact, UrlMappingsHolder urlMappingsHolder) {
        // Resolve http method, url from:
        // 1. UrlMapping rule
        // 2. Controller allowedMethods map
        // 3. default as GET

        // 1. from UrlMapping
        def urlMappingOfAction = urlMappingsHolder.urlMappings.find {
            it.controllerName == controllerArtifact.logicalPropertyName && it.actionName == actionName
        }
        PathItem.HttpMethod httpMethod = PathItem.HttpMethod.GET
        String url
        if (urlMappingOfAction) {
            String httpMethodName = urlMappingOfAction.httpMethod.toUpperCase()
            // http method of grails url-mapping rule is '*' or not in PathItem.HttpMethod enum
            // then we use GET method
            if (httpMethodName == "*" || !PathItem.HttpMethod.values()
                    .collect { it.name() }.contains(httpMethodName)) {
                httpMethodName = "GET"
            }
            httpMethod = PathItem.HttpMethod.valueOf(httpMethodName)
            url = urlMappingOfAction.urlData.urlPattern
        } else {
            // 2. from controller
            def allowedMethods = controllerArtifact.getPropertyValue("allowedMethods")
            if (allowedMethods && allowedMethods[actionName]) {
                httpMethod = PathItem.HttpMethod.valueOf(allowedMethods[actionName] as String)
            }
            def controllerName = controllerArtifact.logicalPropertyName
            UrlCreator urlCreator = urlMappingsHolder.getReverseMapping(controllerName, actionName,
                    controllerArtifact.pluginName, [:])
            url = urlCreator.createURL([controller: controllerName, action: actionName], "utf-8")
        }
        def pathItem = new PathItem()
        pathItem.operation(httpMethod, operation)
        openAPI.paths.addPathItem(url, pathItem)
    }

    Tag buildControllerDoc(GrailsControllerClass grailsControllerClass) {
        def tag = new Tag()
        tag.name = grailsControllerClass.logicalPropertyName.capitalize()
        if (!grailsControllerClass.actions) {
            return tag
        }
        ApiDoc apiDocAnnotation = grailsControllerClass.clazz.getAnnotation(ApiDoc) as ApiDoc
        if (!apiDocAnnotation) {
            return tag
        }
        def tagClosure = apiDocAnnotation.tag()
        if (tagClosure) {
            def tagFromClosure = processClosure(tagClosure, new TagBuilder(reader: this)) as Tag
            // copy default name
            if (!tagFromClosure.name) {
                tagFromClosure.name = tag.name
            }
            tag = tagFromClosure
        }
        openAPI.addTagsItem(tag)
        tag
    }

    def processClosure(Class closureClass, AnnotationBuilder builder) {
        //def builder = builderClass.newInstance(openAPI: openAPI)
        if (closureClass) {
            // call the constructor of Closure(Object owner, Object thisObject)
            Closure closure = closureClass.newInstance(openAPI, openAPI) as Closure
            closure.delegate = builder
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure()
        }
        builder.model
    }

    /**
     * Grails controller original action has an annotation "Action" with member "commandObjects" store
     * action commands classes, we will build parameter schemas on these command class.
     * @param actionName name of the action to build
     * @param grailsControllerClass action belonged grails controller class
     */
    RequestBody buildActionCommandParameters(String actionName, GrailsControllerClass grailsControllerClass) {
        Class plainClass = grailsControllerClass.clazz
        def actionMethods = plainClass.methods.find { it.name == actionName && it.getAnnotation(Action) }
        def actionAnnotation = actionMethods.getAnnotation(Action)
        def commandClasses = actionAnnotation.commandObjects()
        if (commandClasses) {
            // Create schema in components
            def commandClass = commandClasses[0]
            if (!isCommandClass(commandClass)) {
                return null
            }
            Schema schema = buildSchema(commandClass)
            def ref = "#/components/schemas/${schema.name}"
            Content content = new Content()
            content.addMediaType(JSON_MIME, new MediaType(schema: new Schema($ref: ref)))
            content.addMediaType(DEFAULT_MIME, new MediaType(schema: new Schema($ref: ref)))
            return new RequestBody(content: content)
        } else {
            return null
        }
    }

    Map<String, Schema> buildClassProperties(Class<?> aClass) {
        def propertiesMap = [:] as Map<String, Schema>
        aClass.declaredFields.each { field ->
            // skip grails/groovy fields
            switch (field.name) {
                case ~/.*(grails_|\$).*/:
                case "metaClass":
                case "clazz":
                case "constraints":
                case "mappings":
                    return
            }
            def fieldSchema = buildSchema(field.type)
            // @ApiDoc prefer over @ApiDocComment
            def apiDocAnn = field.getAnnotation(ApiDoc)
            def apiDocCommentAnn = field.getAnnotation(ApiDocComment)
            def comments = apiDocAnn ? apiDocAnn.value() : apiDocCommentAnn?.value()
            comments = comments ?: ""
            fieldSchema.description = comments + " \n" + fieldSchema.description
            propertiesMap[field.name] = fieldSchema
        }
        return propertiesMap
    }

    /**
     * Build Schema from command class or domain class
     * @param aClass command class, domain class
     * @return OAS Schema object
     */
    Schema buildSchema(Class aClass) {
        TypeAndFormat typeAndFormat = buildType(aClass)
        String name = aClass.canonicalName
        // check exists schema, avoid infinite loop
        Schema schema = getSchemaFromOpenAPI(name)
        if (schema) {
            return schema
        }
        Map args = [name: name,
                    type: typeAndFormat.type,
                    format: typeAndFormat.format,
                    description: buildSchemaDescription(aClass)]
        schema = typeAndFormat.type == "array" ? new ArraySchema(args) : new Schema(args)
        if (typeAndFormat.type in ["object", "enum"]) {
            openAPI.schema(aClass.canonicalName, schema)
        }
        switch (typeAndFormat.type) {
            case "object":
                schema.properties = buildClassProperties(aClass)
                break
            case "array":
                // try to get array element type
                Class itemClass = aClass.componentType
                if (itemClass && schema instanceof ArraySchema) {
                    schema.items = buildSchema(itemClass)
                }
                break
            case "enum":
                schema.type = "integer"
                schema.setEnum(buildEnumItems(aClass))
                buildEnumDescription(aClass, schema)
                break
        }
        return schema
    }

    /**
     * Build OASv3 type and format from class.
     */
    static TypeAndFormat buildType(Class aClass) {
        TypeAndFormat typeAndFormat = new TypeAndFormat()
        switch (aClass) {
            case String:
            case GString:
                typeAndFormat.type = "string"
                break
            case short:
            case Short:
                typeAndFormat.type = "integer"
                typeAndFormat.format = ""
                break
            case int:
            case Integer:
                typeAndFormat.type = "integer"
                typeAndFormat.format = "int32"
                break
            case long:
            case Long:
                typeAndFormat.type = "integer"
                typeAndFormat.format = "int64"
                break
            case boolean:
            case Boolean:
                typeAndFormat.type = "boolean"
                break
            case double:
            case Double:
                typeAndFormat.type = "number"
                typeAndFormat.format = "double"
                break
            case float:
            case Float:
                typeAndFormat.type = "number"
                typeAndFormat.format = "float"
                break
            case Number:
                typeAndFormat.type = "number"
                break
            case Collection:
            case { aClass.isArray() }:
                typeAndFormat.type = "array"
                break
            case Enum:
                typeAndFormat.type = "enum"
                break
            default:
                typeAndFormat.type = "object"
                break
        }
        return typeAndFormat
    }

    static boolean isCommandClass(Class<?> aClass) {
        Validateable.isAssignableFrom aClass
    }

    static String buildSchemaDescription(Class aClass) {
        ApiDoc apiDocAnnotation = aClass.getAnnotation(ApiDoc) as ApiDoc
        apiDocAnnotation?.value() ?: ""
    }

    Schema getSchemaFromOpenAPI(String name) {
        openAPI.components?.getSchemas()?.get(name)
    }

    /**
     * Use enum id as property value
     */
    static List buildEnumItems(Class enumClass) {
        enumClass.values()?.collect { it.id }
    }

    static void buildEnumDescription(Class aClass, Schema schema) {
        StringBuilder builder = new StringBuilder(schema.description)
        if (schema?.description?.trim()) {
            char endChar = schema.description.charAt(schema.description.length() - 1)
            if (Character.isAlphabetic(endChar) || Character.isIdeographic(endChar)) {
                builder.append(". ")
            }
        }
        builder.append("Enum of: ")
        aClass.values()?.eachWithIndex { enumValue, idx ->
            String idPart = ""
            if (enumValue.hasProperty("id")) {
                idPart = "(${enumValue.id})"
            }
            // append ", " if idx > 0
            if (idx > 0) {
                builder.append(", ")
            }
            builder.append("${enumValue.name()}${idPart}")
        }
        schema.description = builder.toString()
    }

    /**
     * According to the https://swagger.io/docs/specification/data-models/data-types/
     */
    static class TypeAndFormat {
        String type = "object"
        String format = ""
    }
}
