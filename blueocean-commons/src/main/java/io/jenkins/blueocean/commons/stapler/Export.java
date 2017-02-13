package io.jenkins.blueocean.commons.stapler;

import hudson.ExtensionList;
import hudson.PluginWrapper;
import hudson.model.Action;
import io.jenkins.blueocean.commons.stapler.export.DataWriter;
import io.jenkins.blueocean.commons.stapler.export.ExportConfig;
import io.jenkins.blueocean.commons.stapler.export.ExportInterceptor;
import io.jenkins.blueocean.commons.stapler.export.Flavor;
import io.jenkins.blueocean.commons.stapler.export.Model;
import io.jenkins.blueocean.commons.stapler.export.ModelBuilder;
import io.jenkins.blueocean.commons.stapler.export.NamedPathPruner;
import io.jenkins.blueocean.commons.stapler.export.NotExportableException;
import io.jenkins.blueocean.commons.stapler.export.Property;
import io.jenkins.blueocean.commons.stapler.export.TreePruner;
import io.jenkins.blueocean.commons.stapler.export.TreePruner.ByDepth;
import jenkins.model.Jenkins;
import jenkins.security.SecureRequester;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.CustomExportedBean;
import org.kohsuke.stapler.export.ExportedBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Export {

    private static final Logger logger = LoggerFactory.getLogger(Export.class);

    static ModelBuilder MODEL_BUILDER = new ModelBuilder();

    public static void doJson(StaplerRequest req, StaplerResponse rsp, Object bean) throws IOException, ServletException {
        if (req.getParameter("jsonp") == null || permit(req, bean)) {
            rsp.setHeader("X-Jenkins", Jenkins.VERSION);
            rsp.setHeader("X-Jenkins-Session", Jenkins.SESSION_HASH);
            ExportConfig exportConfig = new ExportConfig()
                    .withFlavor(req.getParameter("jsonp") == null ? Flavor.JSON : Flavor.JSONP)
                    .withExportInterceptor(new BlueOceanExportInterceptor())
                    .withPrettyPrint(req.hasParameter("pretty"));
            serveExposedBean(req, rsp, bean, exportConfig);
        } else {
            rsp.sendError(HttpURLConnection.HTTP_FORBIDDEN, "jsonp forbidden; implement jenkins.security.SecureRequester");
        }
    }

    private static boolean permit(StaplerRequest req, Object bean) {
        for (SecureRequester r : ExtensionList.lookup(SecureRequester.class)) {
            if (r.permit(req, bean)) {
                return true;
            }
        }
        return false;
    }

    private static void serveExposedBean(StaplerRequest req, StaplerResponse resp, Object exposedBean, ExportConfig config) throws ServletException, IOException {
        Flavor flavor = config.getFlavor();
        String pad=null;
        resp.setContentType(flavor.contentType);
        Writer w = resp.getCompressedWriter(req);

        if (flavor== Flavor.JSON || flavor== Flavor.JSONP) { // for compatibility reasons, accept JSON for JSONP as well.
            pad = req.getParameter("jsonp");
            if(pad!=null) w.write(pad+'(');
        }

        TreePruner pruner;
        String tree = req.getParameter("tree");
        if (tree != null) {
            try {
                pruner = new NamedPathPruner(tree);
            } catch (IllegalArgumentException x) {
                throw new ServletException("Malformed tree expression: " + x, x);
            }
        } else {
            int depth = 0;
            try {
                String s = req.getParameter("depth");
                if (s != null) {
                    depth = Integer.parseInt(s);
                }
            } catch (NumberFormatException e) {
                throw new ServletException("Depth parameter must be a number");
            }
            pruner = new ByDepth(1 - depth);
        }

        DataWriter dw = flavor.createDataWriter(exposedBean, w, config);
        if (exposedBean instanceof Object[]) {
            // TODO: extend the contract of DataWriter to capture this
            // TODO: make this work with XML flavor (or at least reject this better)
            dw.startArray();
            for (Object item : (Object[])exposedBean)
                writeOne(pruner, dw, item);
            dw.endArray();
        } else {
            writeOne(pruner, dw, exposedBean);
        }

        if(pad!=null) w.write(')');
        w.close();
    }

    private static void writeOne(TreePruner pruner, DataWriter dw, Object item) throws IOException {
        Model p = MODEL_BUILDER.get(item.getClass());
        p.writeTo(item, pruner, dw);
    }

    private Export() {};

    public static class BlueOceanExportInterceptor extends ExportInterceptor{
        @Override
        public Object getValue(Property property, Object model) throws IOException {
            if(model instanceof Action){
                try {
                    Object value = property.getValue(model);

                    //If {@link CustomExportedBean} then return just the String value
                    if(value instanceof CustomExportedBean){
                        return ((CustomExportedBean) value).toExportedObject();
                    }

                    //Check for ExportedBean annotation
                    if(checkForExportedBean(value) && (value.getClass().getAnnotation(ExportedBean.class) == null)){
                        throw new NotExportableException(value.getClass(), model.getClass(), property.name);
                    }
                    return value;
                } catch (Throwable e) {
                    printError(model.getClass(), e);
                    return null;
                }
            }
            return ExportInterceptor.DEFAULT.getValue(property, model);
        }

        private void printError(Class modelClass, Throwable e){
            PluginWrapper plugin = Jenkins.getInstance().getPluginManager().whichPlugin(modelClass);
            String msg;
            if (plugin != null) {
                String url = plugin.getUrl() == null ? "https://issues.jenkins-ci.org/" : plugin.getUrl();
                msg = "BUG: Problem with serializing <" + modelClass + "> belonging to plugin <" + plugin.getLongName() + ">. Report the stacktrace below to the plugin author by visiting " + url;
            } else {
                msg = "BUG: Problem with serializing <" + modelClass + ">";
            }
            if(e != null) {
                logger.error(msg, e);
            }else{
                logger.error(msg);
            }
        }

        private boolean checkForExportedBean(Object value){
            Class type = value.getClass();
            return !(STRING_TYPES.contains(type) ||
                    PRIMITIVE_TYPES.contains(type) ||
                    (type.getComponentType() != null) ||
                    (value instanceof Map) ||
                    (value instanceof Iterable) ||
                    (value instanceof Date) ||
                    (value instanceof Calendar) ||
                    (value instanceof Enum));
        }

        // XXX: Probably these should be exposed from Stapler, for now lets define these here
        private static final Set<Class> STRING_TYPES = new HashSet<Class>(Arrays.asList(
                String.class,
                URL.class
        ));

        private static final Set<Class> PRIMITIVE_TYPES = new HashSet<Class>(Arrays.asList(
                Integer.class,
                Long.class,
                Boolean.class,
                Short.class,
                Character.class,
                Float.class,
                Double.class
        ));
    }
}
