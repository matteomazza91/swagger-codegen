package io.swagger.codegen.languages;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.codegen.CliOption;
import io.swagger.codegen.CodegenConstants;
import io.swagger.codegen.CodegenOperation;
import io.swagger.codegen.CodegenParameter;
import io.swagger.codegen.CodegenResponse;
import io.swagger.codegen.CodegenType;
import io.swagger.codegen.languages.features.BeanValidationFeatures;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.Swagger;

public abstract class AbstractJavaJAXRSServerCodegen extends AbstractJavaCodegen implements BeanValidationFeatures {
    /**
     * Name of the sub-directory in "src/main/resource" where to find the
     * Mustache template for the JAX-RS Codegen.
     */
    protected static final String JAXRS_TEMPLATE_DIRECTORY_NAME = "JavaJaxRS";
    protected String implFolder = "src/main/java";
    protected String testResourcesFolder = "src/test/resources";
    protected String title = "Swagger Server";

    public static final String USE_ANNOTATED_BASE_PATH = "useAnnotatedBasePath";
    
    protected boolean useBeanValidation = true;
    protected boolean useAnnotatedBasePath = false;

    static Logger LOGGER = LoggerFactory.getLogger(AbstractJavaJAXRSServerCodegen.class);

    public AbstractJavaJAXRSServerCodegen() {
        super();
        
        sourceFolder = "src/gen/java";
        invokerPackage = "io.swagger.api";
        artifactId = "swagger-jaxrs-server";
        dateLibrary = "legacy"; //TODO: add joda support to all jax-rs

        apiPackage = "io.swagger.api";
        modelPackage = "io.swagger.model";

        additionalProperties.put("title", title);
        // java inflector uses the jackson lib
        additionalProperties.put("jackson", "true");

        cliOptions.add(new CliOption(CodegenConstants.IMPL_FOLDER, CodegenConstants.IMPL_FOLDER_DESC));
        cliOptions.add(new CliOption("title", "a title describing the application"));

        cliOptions.add(CliOption.newBoolean(USE_BEANVALIDATION, "Use BeanValidation API annotations"));
        cliOptions.add(new CliOption("serverPort", "The port on which the server should be started"));
        cliOptions.add(CliOption.newBoolean(USE_ANNOTATED_BASE_PATH, "Use @Path annotations for basePath"));

    }


    // ===============
    // COMMONS METHODS
    // ===============

    @Override
    public CodegenType getTag() {
        return CodegenType.SERVER;
    }

    @Override
    public void processOpts() {
        super.processOpts();

        if (additionalProperties.containsKey(CodegenConstants.IMPL_FOLDER)) {
            implFolder = (String) additionalProperties.get(CodegenConstants.IMPL_FOLDER);
        }

        if (additionalProperties.containsKey(USE_BEANVALIDATION)) {
            this.setUseBeanValidation(convertPropertyToBoolean(USE_BEANVALIDATION));
        }

        if (useBeanValidation) {
            writePropertyBack(USE_BEANVALIDATION, useBeanValidation);
        }

        if (additionalProperties.containsKey(USE_ANNOTATED_BASE_PATH)) {
            boolean useAnnotatedBasePathProp = convertPropertyToBooleanAndWriteBack(USE_ANNOTATED_BASE_PATH);
            this.setUseAnnotatedBasePath(useAnnotatedBasePathProp);
        }
    }

    @Override
    public void preprocessSwagger(Swagger swagger) {
        if ( "/".equals(swagger.getBasePath()) ) {
            swagger.setBasePath("");
        }

        if (!this.additionalProperties.containsKey("serverPort")) {
            final String host = swagger.getHost();
            String port = "8080"; // Default value for a JEE Server
            if ( host != null ) {
                String[] parts = host.split(":");
                if ( parts.length > 1 ) {
                    port = parts[1];
                }
            }

            this.additionalProperties.put("serverPort", port);
        }

        if ( swagger.getPaths() != null ) {
            for ( String pathname : swagger.getPaths().keySet() ) {
                Path path = swagger.getPath(pathname);
                if ( path.getOperations() != null ) {
                    for ( Operation operation : path.getOperations() ) {
                        if ( operation.getTags() != null ) {
                            List<Map<String, String>> tags = new ArrayList<Map<String, String>>();
                            for ( String tag : operation.getTags() ) {
                                Map<String, String> value = new HashMap<String, String>();
                                value.put("tag", tag);
                                value.put("hasMore", "true");
                                tags.add(value);
                            }
                            if ( tags.size() > 0 ) {
                                tags.get(tags.size() - 1).remove("hasMore");
                            }
                            if ( operation.getTags().size() > 0 ) {
                                String tag = operation.getTags().get(0);
                                operation.setTags(Arrays.asList(tag));
                            }
                            operation.setVendorExtension("x-tags", tags);
                        }
                    }
                }
            }
        }
    }

    @Override
    public Map<String, Object> postProcessOperations(Map<String, Object> objs) {
        @SuppressWarnings("unchecked")
        Map<String, Object> operations = (Map<String, Object>) objs.get("operations");
        if ( operations != null ) {
            @SuppressWarnings("unchecked")
            List<CodegenOperation> ops = (List<CodegenOperation>) operations.get("operation");
            for ( CodegenOperation operation : ops ) {
                if (operation.hasConsumes == Boolean.TRUE) {
                    Map<String, String> firstType = operation.consumes.get(0);
                    if (firstType != null) {
                        if ("multipart/form-data".equals(firstType.get("mediaType"))) {
                            operation.isMultipart = Boolean.TRUE;
                        }
                    }
                }

                boolean isMultipartPost = false;
                List<Map<String, String>> consumes = operation.consumes;
                if(consumes != null) {
                    for(Map<String, String> consume : consumes) {
                        String mt = consume.get("mediaType");
                        if(mt != null) {
                            if(mt.startsWith("multipart/form-data")) {
                                isMultipartPost = true;
                            }
                        }
                    }
                }

                for(CodegenParameter parameter : operation.allParams) {
                    if(isMultipartPost) {
                        parameter.vendorExtensions.put("x-multipart", "true");
                    }
                }

                List<CodegenResponse> responses = operation.responses;
                if ( responses != null ) {
                    for ( CodegenResponse resp : responses ) {
                        if ( "0".equals(resp.code) ) {
                            resp.code = "200";
                        }
                        
                        // set WebApplicationException as vendorExtension for response code different from 200
                        setWebApplicationException(resp);

                        if (resp.baseType == null) {
                            resp.dataType = "void";
                            resp.baseType = "Void";
                            // set vendorExtensions.x-java-is-response-void to true as baseType is set to "Void"
                            resp.vendorExtensions.put("x-java-is-response-void", true);
                        }

                        if ("array".equals(resp.containerType)) {
                            resp.containerType = "List";
                        } else if ("map".equals(resp.containerType)) {
                            resp.containerType = "Map";
                        }
                    }
                }

                if ( operation.returnBaseType == null ) {
                    operation.returnType = "void";
                    operation.returnBaseType = "Void";
                    // set vendorExtensions.x-java-is-response-void to true as returnBaseType is set to "Void"
                    operation.vendorExtensions.put("x-java-is-response-void", true);
                }

                if ("array".equals(operation.returnContainer)) {
                    operation.returnContainer = "List";
                } else if ("map".equals(operation.returnContainer)) {
                    operation.returnContainer = "Map";
                }
            }
        }
        return objs;
    }

    
    /**
     * set WebApplicationException as vendorExtension for response code different from 200
     * @param resp
     */
    private void setWebApplicationException(CodegenResponse resp) {
    	if(resp.code != null){
    		Map<String,Object> webAppException;
            switch(resp.code){
            	case "400": 
            		webAppException = new HashMap<>();
            		webAppException.put("className", "javax.ws.rs.BadRequestException");
            		webAppException.put("classSimpleName", "BadRequestException");
            		webAppException.put("isChildClass", true);
            		
            		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);
            		break;
            	case "401":
            		webAppException = new HashMap<>();
            		webAppException.put("className", "javax.ws.rs.NotAuthorizedException");
            		webAppException.put("classSimpleName", "NotAuthorizedException");
            		webAppException.put("isChildClass", true);
            		
            		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);
            		break;
            	case "403":
            		webAppException = new HashMap<>();
            		webAppException.put("className", "javax.ws.rs.ForbiddenException");
            		webAppException.put("classSimpleName", "ForbiddenException");
            		webAppException.put("isChildClass", true);
            		
            		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);
            		break;
            	case "404":
            		webAppException = new HashMap<>();
            		webAppException.put("className", "javax.ws.rs.NotFoundException");
            		webAppException.put("classSimpleName", "NotFoundException");
            		webAppException.put("isChildClass", true);
            		
            		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);
            		break;
            	case "405":
            		webAppException = new HashMap<>();
            		webAppException.put("className", "javax.ws.rs.NotAllowedException");
            		webAppException.put("classSimpleName", "NotAllowedException");
            		webAppException.put("isChildClass", true);
            		
            		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);
            		break;
            	case "406":
            		webAppException = new HashMap<>();
            		webAppException.put("className", "javax.ws.rs.NotAcceptableException");
            		webAppException.put("classSimpleName", "NotAcceptableException");
            		webAppException.put("isChildClass", true);
            		
            		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);
            		break;
            	case "415":
            		webAppException = new HashMap<>();
            		webAppException.put("className", "javax.ws.rs.NotSupportedException");
            		webAppException.put("classSimpleName", "NotSupportedException");
            		webAppException.put("isChildClass", true);
            		
            		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);
            		break;
            	case "500":
            		webAppException = new HashMap<>();
            		webAppException.put("className", "javax.ws.rs.InternalServerErrorException");
            		webAppException.put("classSimpleName", "InternalServerErrorException");
            		webAppException.put("isChildClass", true);
            		
            		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);
            		break;
            	case "503":
            		webAppException = new HashMap<>();
            		webAppException.put("className", "javax.ws.rs.ServiceUnavailableException");
            		webAppException.put("classSimpleName", "ServiceUnavailableException");
            		webAppException.put("isChildClass", true);
            		
            		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);
            		break;
            	default:
            		if(resp.code.compareTo("3") >= 0){//this response should throw a jaxrs exception that is not present in the switch
                		webAppException = new HashMap<>();
                		webAppException.put("className", "javax.ws.rs.WebApplicationException");
                		webAppException.put("classSimpleName", "WebApplicationException");

                		resp.vendorExtensions.put("x-jaxrs-WebApplicationException", webAppException);	
            		}
            }
        }
		
	}


	@Override
    public String toApiName(final String name) {
        String computed = name;
        if ( computed.length() == 0 ) {
            return "DefaultApi";
        }
        computed = sanitizeName(computed);
        return camelize(computed) + "Api";
    }

    @Override
    public String apiFilename(String templateName, String tag) {
        String result = super.apiFilename(templateName, tag);

        if ( templateName.endsWith("Impl.mustache") ) {
            int ix = result.lastIndexOf('/');
            result = result.substring(0, ix) + "/impl" + result.substring(ix, result.length() - 5) + "ServiceImpl.java";
            result = result.replace(apiFileFolder(), implFileFolder(implFolder));
        } else if ( templateName.endsWith("Factory.mustache") ) {
            int ix = result.lastIndexOf('/');
            result = result.substring(0, ix) + "/factories" + result.substring(ix, result.length() - 5) + "ServiceFactory.java";
            result = result.replace(apiFileFolder(), implFileFolder(implFolder));
        } else if ( templateName.endsWith("Service.mustache") ) {
            int ix = result.lastIndexOf('.');
            result = result.substring(0, ix) + "Service.java";
        }
        return result;
    }

    private String implFileFolder(String output) {
        return outputFolder + "/" + output + "/" + apiPackage().replace('.', '/');
    }

    public void setUseBeanValidation(boolean useBeanValidation) {
        this.useBeanValidation = useBeanValidation;
    }

    public void setUseAnnotatedBasePath(boolean useAnnotatedBasePath) {
        this.useAnnotatedBasePath = useAnnotatedBasePath;
    }

}
