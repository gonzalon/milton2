/*
 * Copyright 2012 McEvoy Software Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.milton.http.json;

import io.milton.resource.ReplaceableResource;
import io.milton.common.FileUtils;
import io.milton.common.Utils;
import io.milton.event.EventManager;
import io.milton.event.PutEvent;
import io.milton.http.*;
import io.milton.http.Request.Method;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.DeletableResource;
import io.milton.resource.PostableResource;
import io.milton.resource.PutableResource;
import io.milton.resource.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import net.sf.json.JSON;
import net.sf.json.JSONSerializer;
import net.sf.json.JsonConfig;
import net.sf.json.util.CycleDetectionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Will use milton's PUT framework to support file uploads using POST and
 * multipart encoding
 *
 * This will save the uploaded files with their given names into the parent
 * collection resource.
 *
 * If a file already exists with the same name a ConflictException is thrown,
 * unless you set the _autoname request parameter. If this parameter is present
 * (ie with any value) the file will be saved with a non-conflicting file name
 *
 * Save file information is returned as JSON in the response content
 *
 * @author brad
 */
public class PutJsonResource extends JsonResource implements PostableResource {

    private static final Logger log = LoggerFactory.getLogger(PutJsonResource.class);
    public static final String PARAM_AUTONAME = "_autoname";
    public static final String PARAM_NAME = "name";
    public static final String PARAM_OVERWRITE = "overwrite";
	private final EventManager eventManager;
    private final PutableResource wrapped;
    private final String href;
    private List<NewFile> newFiles;

    public PutJsonResource(PutableResource putableResource, String href, EventManager eventManager) {
        super(putableResource, Request.Method.PUT.code, null);
		this.eventManager = eventManager;
        this.wrapped = putableResource;
        this.href = href;
    }

    @Override
    public String getContentType(String accepts) {
        String s = "application/x-javascript; charset=utf-8";
        s = "text/plain";
        return s;
        //return "application/json";
    }

    
	@Override
    public String processForm(Map<String, String> parameters, Map<String, FileItem> files) throws ConflictException, NotAuthorizedException, BadRequestException {
		log.info("processForm: " + wrapped.getClass());
        if (files.isEmpty()) {
            log.debug("no files uploaded");
            return null;
        }
        newFiles = new ArrayList<NewFile>();
        for (FileItem file : files.values()) {
            NewFile nf = new NewFile();
            String ua = HttpManager.request().getUserAgentHeader();
            String f = Utils.truncateFileName(ua, file.getName()); 
            nf.setOriginalName(f);
            nf.setContentType(file.getContentType());
            nf.setLength(file.getSize());
            String newName = getName(f, parameters);
            String newHref = buildNewHref(href, newName);
            nf.setHref(newHref);
            newFiles.add(nf);
            log.debug("creating resource: " + newName + " size: " + file.getSize());
            InputStream in = null;
            Resource newResource;
            try {
                in = file.getInputStream();
                Resource existing = wrapped.child(newName);
                if( existing != null ) {
                    if( existing instanceof ReplaceableResource ) {
                        log.trace("existing resource is replaceable, so replace content");
                        ReplaceableResource rr = (ReplaceableResource) existing;
                        rr.replaceContent(in, null);
						log.trace("completed POST processing for file. Updated: " + existing.getName());
						eventManager.fireEvent(new PutEvent(rr));
                    } else {
                        log.trace("existing resource is not replaceable, will be deleted");
                        if( existing instanceof DeletableResource ) {
                            DeletableResource dr = (DeletableResource) existing;
                            dr.delete();
							newResource = wrapped.createNew(newName, in, file.getSize(), file.getContentType());							
							log.trace("completed POST processing for file. Deleted, then created: " + newResource.getName());
							eventManager.fireEvent(new PutEvent(newResource));
                        } else {
                            throw new BadRequestException(existing, "existing resource could not be deleted, is not deletable");
                        }
                    }					
                } else {
                    newResource = wrapped.createNew(newName, in, file.getSize(), file.getContentType());
					log.trace("completed POST processing for file. Created: " + newResource.getName());
					eventManager.fireEvent(new PutEvent(newResource));
                }                
            } catch (NotAuthorizedException ex) {
                throw new RuntimeException(ex);
            } catch (BadRequestException ex) {
                throw new RuntimeException(ex);
            } catch (ConflictException ex) {
                throw new RuntimeException(ex);
            } catch (IOException ex) {
                throw new RuntimeException("Exception creating resource", ex);
            } finally {
                FileUtils.close(in);
            }            
        }
        log.trace("completed all POST processing");
        return null;
    }

    /**
     * Returns a JSON representation of the newly created hrefs
     *
     * @param out
     * @param range
     * @param params
     * @param contentType
     * @throws IOException
     * @throws NotAuthorizedException
     */
	@Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) throws IOException, NotAuthorizedException {
        JsonConfig cfg = new JsonConfig();
        cfg.setIgnoreTransientFields(true);
        cfg.setCycleDetectionStrategy(CycleDetectionStrategy.LENIENT);

        NewFile[] arr;
        if (newFiles != null) {
            arr = new NewFile[newFiles.size()];
			newFiles.toArray(arr);
        } else {
            arr = new NewFile[0];
        }
        Writer writer = new PrintWriter(out);
        JSON json = JSONSerializer.toJSON(arr, cfg);
        json.write(writer);
        writer.flush();
    }

    @Override
    public Method applicableMethod() {
        return Method.PUT;
    }

    /**
     * We dont return anything, so best not use json
     *
     * @param accepts
     * @return
     */
//    @Override
//    public String getContentType(String accepts) {
//        return "text/html";
//    }
    private String getName(String filename, Map<String, String> parameters) throws ConflictException, NotAuthorizedException, BadRequestException {
        String initialName = filename;
        if( parameters.containsKey(PARAM_NAME)) {
            initialName = parameters.get(PARAM_NAME);
        }
        boolean nonBlankName = initialName != null && initialName.trim().length() > 0;
        boolean autoname = (parameters.get(PARAM_AUTONAME) != null);
        boolean overwrite = (parameters.get(PARAM_OVERWRITE) != null);
        if (nonBlankName) {
            Resource child = wrapped.child(initialName);
            if (child == null) {
                log.trace("no existing file with that name");
                return initialName;
            } else {
                if (overwrite) {
                    log.trace("file exists, and overwrite parameters is set, so allow overwrite: " + initialName);
                    return initialName;
                } else {
                    if (!autoname) {
                        log.warn("Conflict: Can't create resource with name " + initialName + " because it already exists. To rename automatically use request parameter: " + PARAM_AUTONAME + ", or to overwrite use " + PARAM_OVERWRITE);
                        throw new ConflictException(this);
                    } else {
                        log.trace("file exists and autoname is set, so will find acceptable name");
                    }
                }
            }
        } else {
            initialName = getDateAsName("upload");
            log.trace("no name given in request");
        }
        return findAcceptableName(initialName);
    }

    private String getDateAsName(String base) {
        Calendar cal = Calendar.getInstance();
        return base + "_" + cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.MONTH) + "-" + cal.get(Calendar.DAY_OF_MONTH);
    }

    private String findAcceptableName(String initialName) throws ConflictException, NotAuthorizedException, BadRequestException {
        String baseName = FileUtils.stripExtension(initialName);
        String ext = FileUtils.getExtension(initialName);
        return findAcceptableName(baseName, ext, 1);
    }

    private String findAcceptableName(String baseName, String ext, int i) throws ConflictException, NotAuthorizedException, BadRequestException {
        String candidateName = baseName + "_" + i;
        if (ext != null && ext.length() > 0) {
            candidateName += "." + ext;
        }
        if (wrapped.child(candidateName) == null) {
            return candidateName;
        } else {
            if (i < 100) {
                return findAcceptableName(baseName, ext, i + 1);
            } else {
                log.warn("Too many files with similar names: " + candidateName);
                throw new ConflictException(this);
            }
        }
    }

    private String buildNewHref(String href, String newName) {
        String s = href;
        int pos = href.lastIndexOf("_DAV");
        s = s.substring(0, pos - 1);
        if (!s.endsWith("/")) {
            s += "/";
        }
        s += newName;
        return s;
    }

    public class NewFile {

        private String href;
        private String originalName;
        private long length;
        private String contentType;

        public String getHref() {
            return href;
        }

        public void setHref(String href) {
            this.href = href;
        }

        public String getOriginalName() {
            return originalName;
        }

        public void setOriginalName(String originalName) {
            this.originalName = originalName;
        }

        public long getLength() {
            return length;
        }

        public void setLength(long length) {
            this.length = length;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }
    }
}
