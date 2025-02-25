package org.nuxeo.ecm.restapi.server.jaxrs;

import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.nuxeo.ecm.automation.core.util.DocumentHelper;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.ecm.core.schema.FacetNames;
import org.nuxeo.ecm.webengine.model.WebObject;
import org.nuxeo.ecm.webengine.model.impl.DefaultObject;
import org.nuxeo.runtime.api.Framework;
import org.onlyoffice.utils.JwtManager;
import org.onlyoffice.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/onlyoffice")
@WebObject(type = "onlyoffice")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class Callback extends DefaultObject {

    private static final Logger logger = LoggerFactory.getLogger(Callback.class);

    private JwtManager jwtManager;
    private Utils utils;

    @Override
    protected void initialize(Object... args) {
        super.initialize(args);

        jwtManager = Framework.getService(JwtManager.class);
        utils = Framework.getService(Utils.class);
    }

    @POST
    @Path("callback/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Object postCallback(@PathParam("id") String id, @QueryParam("digest") String digest, InputStream input) {
        Status code = Status.OK;
        Exception error = null;

        try {
            JSONObject json = new JSONObject(IOUtils.toString(input, Charset.defaultCharset()));
            if (jwtManager.isEnabled()) {
                String token = json.optString("token");
                Boolean inBody = true;
                
                if (token == null || token == "") {
                    List<String> values = getContext().getHttpHeaders().getRequestHeader("Authorization");
                    String header = values.isEmpty() ? null : values.get(0);
                    token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : header;
                    inBody = false;
                }

                if (token == null || token == "") {
                    throw new SecurityException("Expected JWT");
                }

                if (!jwtManager.verify(token)) {
                    throw new SecurityException("JWT verification failed");
                }

                JSONObject bodyFromToken = new JSONObject(
                        new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"));

                if (inBody) {
                    json = bodyFromToken;
                } else {
                    json = bodyFromToken.getJSONObject("payload");
                }
            }

            CoreSession session = getContext().getCoreSession();
            DocumentModel model = session.getDocument(new IdRef(id));
            if (digest != null) {
                json.put("digest", digest);
            }
            processCallback(session, model, json);

        } catch (SecurityException ex) {
            code = Status.UNAUTHORIZED;
            error = ex;
            logger.error("Security error while saving document " + id, ex);
        } catch (Exception ex) {
            code = Status.INTERNAL_SERVER_ERROR;
            error = ex;
            logger.error("Error while saving document " + id, ex);
        }

        HashMap<String, Object> response = new HashMap<String, Object>();
        if (error != null) {
            response.put("error", 1);
            response.put("message", error.getMessage());
        } else {
            response.put("error", 0);
        }

        try {
            return Response.status(code).entity(new JSONObject(response).toString(2)).build();
        } catch (Exception e) {
            logger.error("Error while processing callback for " + id, e);
            return Response.status(code).build();
        }
    }

    private void processCallback(CoreSession session, DocumentModel model, JSONObject json) throws Exception {
        switch (json.getInt("status")) {
        case 0:
            logger.error("ONLYOFFICE has reported that no doc with the specified key can be found");
            model.removeLock();
            break;
        case 1:
            if (!model.isLocked()) {
                logger.info("Document open for editing, locking document");
                model.setLock();
            } else {
                logger.debug("Document already locked, another user has entered/exited");
            }
            break;
        case 2:
            logger.info("Document Updated, changing content");
            model.removeLock();
            updateDocument(session, model, json.getString("key"), json.getString("url"), json.optString("digest"));
            break;
        case 3:
            logger.error("ONLYOFFICE has reported that saving the document has failed");
            model.removeLock();
            break;
        case 4:
            logger.info("No document updates, unlocking node");
            model.removeLock();
            break;
        }
    }

    private void updateDocument(CoreSession session, DocumentModel model, String changeToken, String url, String digest) throws Exception {
        Blob original;
        Blob saved;
        if (digest.isEmpty()) {
            original = getBlob(model, "file:content");
            saved = Blobs.createBlob(new URL(url).openStream(), original.getMimeType(), original.getEncoding());
            saved.setFilename(original.getFilename());

            DocumentHelper.addBlob(model.getProperty("file:content"), saved);
        } else {
            List<Map<String, Serializable>> files = (List<Map<String, Serializable>>) model.getPropertyValue("files:files");
            boolean check = false;
            for (int i=0;i<files.size();i++){
                Map<String, Serializable> map = files.get(i);
                original = (Blob) map.get("file");
                if (digest.equals(original.getDigest())){
                    saved = Blobs.createBlob(new URL(url).openStream(), original.getMimeType(), original.getEncoding());
                    saved.setFilename(original.getFilename());
                    files.set(i,DocumentHelper.createBlobHolderMap(saved));
                    model.setPropertyValue("files:files", (Serializable) files);
                    check = true;
                    break;
                }
            }
            if (!check) {
                return;
            }
        }


        if (model.hasFacet(FacetNames.VERSIONABLE)) {
            VersioningOption vo = VersioningOption.MINOR;
            model.putContextData(VersioningService.VERSIONING_OPTION, vo);
        }

        model.putContextData(CoreSession.CHANGE_TOKEN, utils.getChangeToken(changeToken));

        session.saveDocument(model);
        session.save();
    }

    private Blob getBlob(DocumentModel model, String xpath) {
        Blob blob = (Blob) model.getPropertyValue(xpath);
        if (blob == null) {
            BlobHolder bh = model.getAdapter(BlobHolder.class);
            if (bh != null) {
                blob = bh.getBlob();
            }
        }
        return blob;
    }
}
