package org.onlyoffice.web;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.sun.org.apache.bcel.internal.generic.ATHROW;
import org.json.JSONObject;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.model.impl.ListProperty;
import org.nuxeo.ecm.tokenauth.service.TokenAuthenticationService;
import org.nuxeo.ecm.webengine.model.WebContext;
import org.nuxeo.ecm.webengine.model.WebObject;
import org.nuxeo.ecm.webengine.model.impl.ModuleRoot;
import org.nuxeo.runtime.api.Framework;
import org.onlyoffice.utils.ConfigManager;
import org.onlyoffice.utils.JwtManager;
import org.onlyoffice.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Path("/onlyedit")
@WebObject(type = "onlyedit")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class Editor extends ModuleRoot {

    private static final Logger logger = LoggerFactory.getLogger(Editor.class);

    private JwtManager jwtManager;
    private ConfigManager config;
    private Utils utils;

    private TokenAuthenticationService authService;

    @Override
    protected void initialize(Object... args) {
        super.initialize(args);

        jwtManager = Framework.getService(JwtManager.class);
        config = Framework.getService(ConfigManager.class);
        utils = Framework.getService(Utils.class);
        authService = Framework.getService(TokenAuthenticationService.class);
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.TEXT_HTML)
    public Object getEdit(@PathParam("id") String id, @QueryParam("mode") String mode, @QueryParam("index") String indexAtt, @QueryParam("digest") String digest) {
        WebContext ctx = getContext();
        CoreSession session = ctx.getCoreSession();
        DocumentModel model = session.getDocument(new IdRef(id));

        try {
            return getView("index")
                    .arg("config", getConfig(ctx, model, mode, indexAtt, digest))
                    .arg("docUrl", config.getDocServUrl())
                    .arg("docTitle", model.getTitle());
        } catch (IllegalArgumentException e) {
            logger.error("Error while opening editor for " + id +": " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        } catch (Exception e) {
            logger.error("Error while opening editor for " + id, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    private JSONObject getConfig(WebContext ctx, DocumentModel model, String mode, String indexAtt, String digest) throws Exception {
        String user = ctx.getPrincipal().getName();
        String token = authService.acquireToken(user, "ONLYOFFICE", "editor", "auth", "rw");
        String baseUrl = ctx.getBaseURL();
        String repoName = ctx.getCoreSession().getRepositoryName();
        String locale = ctx.getLocale().toLanguageTag();

        JSONObject responseJson = new JSONObject();
        JSONObject documentObject = new JSONObject();
        JSONObject editorConfigObject = new JSONObject();
        JSONObject userObject = new JSONObject();
        JSONObject permObject = new JSONObject();

        String docId = model.getId();
        String docTitle = "";
        String docFilename = "";
        String docExt = "";
        String contentUrl = "";
        String callbackUrl = "";

        if (indexAtt == null) {
            docTitle = model.getTitle();
            docFilename = model.getAdapter(BlobHolder.class).getBlob().getFilename();
            contentUrl = String.format("%s/nuxeo/nxfile/%s/%s/file:content/%s?token=%s", baseUrl, repoName, docId, docFilename, token);
            callbackUrl = String.format("%s/nuxeo/api/v1/onlyoffice/callback/%s?token=%s", baseUrl, docId, token);
        } else {
            if (digest == null) {
                throw new IllegalArgumentException("Digest is null");
            }
            List<Map<String, Serializable>> files = (List<Map<String, Serializable>>) model.getPropertyValue("files:files");
            Map<String, Serializable> map = files.get(Integer.parseInt(indexAtt));
            Blob blob = (Blob) map.get("file");

            if (!digest.equals(blob.getDigest())){
                throw new IllegalArgumentException("Digest is incorrect");
            }

            docFilename = blob.getFilename();
            docTitle = docFilename;
            contentUrl = String.format("%s/nuxeo/nxfile/%s/%s/files:files/%s/file/%s?token=%s", baseUrl, repoName, docId, indexAtt, docFilename, token);
            callbackUrl = String.format("%s/nuxeo/api/v1/onlyoffice/callback/%s?digest=%s&token=%s", baseUrl, docId, digest, token);
        }

        docExt = utils.getFileExtension(docFilename);
        Boolean toEdit = mode != null && mode.equals("edit");

        responseJson.put("type", "desktop");
        responseJson.put("width", "100%");
        responseJson.put("height", "100%");
        responseJson.put("documentType", utils.getDocumentType(docExt));

        responseJson.put("document", documentObject);
        documentObject.put("title", docTitle);
        documentObject.put("url", contentUrl);
        documentObject.put("fileType", docExt);
        documentObject.put("key", utils.getDocumentKey(model, indexAtt));
        documentObject.put("permissions", permObject);
        permObject.put("edit", toEdit);

        responseJson.put("editorConfig", editorConfigObject);
        editorConfigObject.put("lang", locale);
        editorConfigObject.put("mode", toEdit ? "edit" : "view");
        editorConfigObject.put("callbackUrl", callbackUrl);
        editorConfigObject.put("user", userObject);
        userObject.put("id", user);
        userObject.put("name", user);

        if (jwtManager.isEnabled()) {
            responseJson.put("token", jwtManager.createToken(responseJson));
        }

        return responseJson;
    }
}
