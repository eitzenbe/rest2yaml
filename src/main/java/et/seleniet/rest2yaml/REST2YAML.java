package et.seleniet.rest2yaml;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 *
 * run REST2YAML with configured arguments to create jitest.yaml
 *
 * mvn exec:exec
 *
 * if you want to view it in swagger
 *
 * cp -r /doc/restapi/jitest.yaml /srv/swagger/
 *
 * To create static html install bootprint and run it in:
 *
 * cd /home/thomas/Workspace/JiTest/jitest-jira-plugin/doc/restapi
 *
 * bootprint openapi jitest.yaml .
 *
 */
public class REST2YAML {
  public static void main(String[] args) throws Exception {
    int i = 0;
    while (i < args.length && !args[i].equals("--")) {
      i++;
    }


    File f = new File(args[i+1]);
    String header = new String(Files.readAllBytes(Paths.get(f.getAbsolutePath())), "UTF-8");
    REST2YAML inst = new REST2YAML(args, i, header);

    String yaml = inst.convert2YAML();
    Path yamlfile = Paths.get(args[i+2]);
    System.out.println("Saving yaml to " + yamlfile);
    Files.write(yamlfile, yaml.getBytes("UTF-8"));
  }

  private File[]         root;
  private String       header;

  private List<String> models = new ArrayList<>();

  private REST2YAML(String[] args, int rootfoldernum, String header) {
    this.root = new File[rootfoldernum];
    for (int i = 0; i < rootfoldernum; i++) {
      root[i] = new File(args[i]);
    }
    this.header = header;
  }

  private String convert2YAML() {
    System.out.println("Checking folder " + root[0]);
    File currFolder = root[0];
    List<File> folders = new ArrayList<>();
    Collections.addAll(folders, root);
    while (currFolder != null) {
      for (File f : Objects.requireNonNull(currFolder.listFiles())) {
        if (f.isDirectory()) {
          folders.add(f);
        } else if (f.getName().endsWith(".java")) {
          try {
            System.out.println("Parsing File " + f.getAbsolutePath());
            inspectJavaFile(f);
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
      folders.remove(currFolder);
      currFolder = null;
      if (!folders.isEmpty()) {
        currFolder = folders.get(0);
      }
    }

    StringBuilder yaml = new StringBuilder();

    yaml.append("swagger: \"2.0\"\n").append(header).append("paths:\n");
    for (String path : entries.keySet()) {
      yaml.append("  ").append(path).append(":\n");
      for (RESTEntry e : entries.get(path)) {
        yaml.append("    ").append(e.method).append(":\n");
        yaml.append("      ").append("tags:\n");
        yaml.append("       - ").append(e.tag).append("\n");
        yaml.append(e.summary);
        yaml.append(e.description);
        yaml.append(e.parameters);
        yaml.append(e.responses);

      }
    }
    if (!models.isEmpty()) {
      yaml.append("definitions:\n");
      yaml.append("  JavaStringStringMap:\n");
      yaml.append("    additionalProperties:\n");
      yaml.append("      type: string\n");

      for (String model : models) {
        String fname = model.replace('.', File.separatorChar);
        yaml.append("  ").append(model).append(":\n");
        yaml.append("    type: object\n");

        // lookup model java file inside of root folder somewhere
        //TODO workaround for now simply skip packages but we actually need the whole chain to comply 
        String fnameTrunc = fname.substring(fname.lastIndexOf('/')+1, fname.length());
        File modelFile = findFile(fnameTrunc + ".java");
        if (modelFile == null) {
          System.err.println("Unable to find java model file " + fname + ".java");
          continue;
        }
        try {
          inspectJavaModelFile(modelFile, yaml);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    return yaml.toString();
  }

  private File findFile(String fname) {
    File currFolder = root[0];
    List<File> folders = new ArrayList<>();
    Collections.addAll(folders, root);
    while (currFolder != null) {
      for (File f : Objects.requireNonNull(currFolder.listFiles())) {
        if (f.isDirectory()) {
          folders.add(f);
        } else if (f.getName().equals(fname)) {
          return f;
        }
      }
      folders.remove(currFolder);
      currFolder = null;
      if (!folders.isEmpty()) {
        currFolder = folders.get(0);
      }
    }
    return null;
  }

  private Map<String, List<RESTEntry>> entries = new HashMap<>();

  private void inspectJavaFile(File pFile) throws IOException {
    CompilationUnit cu;
    try (FileInputStream in = new FileInputStream(pFile)) {
      cu = JavaParser.parse(in);
    }

    RESTAPIClassChecker<Object> classparser = new RESTAPIClassChecker<>();
    classparser.visit(cu, null);

    if (classparser.isRESTAPI()) {
      RESTAPIMethodLister<Object> mthlister = new RESTAPIMethodLister<>(classparser.getClassPath(), entries);
      mthlister.visit(cu, null);
    }
  }

  private void inspectJavaModelFile(File pFile, StringBuilder yaml) throws IOException {
    CompilationUnit cu;
    try (FileInputStream in = new FileInputStream(pFile)) {
      cu = JavaParser.parse(in);
    }

    ModelClassChecker<String> classparser = new ModelClassChecker<>();
    classparser.visit(cu, null);

    if (!classparser.required.isEmpty()) {
      yaml.append("    required:\n");
      for (String reqvar : classparser.required) {
        yaml.append("      - ").append(reqvar).append("\n");
      }
    }
    yaml.append(classparser.yaml);
  }

  private static String getMemberValue(AnnotationExpr a) {
    return getMemberValue(a, false);
  }

  private static String getMemberValue(AnnotationExpr a, @SuppressWarnings("SameParameterValue") boolean trimQuotes) {
    String str = ((SingleMemberAnnotationExpr) a).getMemberValue().toString();

    if (trimQuotes && str.startsWith("\"")) {
      str = str.substring(1);
    }
    if (trimQuotes && str.endsWith("\"")) {
      str = str.substring(0, str.length() - 1);
    }
    return str;
  }

  private static String javaDoc2Line(String jdoc) {

    String line = jdoc.trim();
    if (line.startsWith("/**")) {
      line = line.substring(3, line.length() - 2);
    }
    line = line.replaceAll("[ ]*\\*", "").replace('\n', ' ').replaceAll(" {2}", " ").trim();
    return line;
  }

  private static String unquoteExample(String example) {
    if (example.startsWith("\"")) {
      example = example.substring(1, example.length() - 1);
    }
    example = example.replaceAll("\\\\\"", "\"");
    return example;
  }

  /**
   * Simple visitor implementation for visiting MethodDeclaration nodes.
   */
  private class RESTAPIClassChecker<A> extends VoidVisitorAdapter<A> {

    private boolean restapi;
    private String  classPath;

    @Override
    public void visit(ClassOrInterfaceDeclaration c, Object arg) {
      restapi = false;
      List<AnnotationExpr> annotations = c.getAnnotations();
      if (annotations != null) {
        for (AnnotationExpr a : annotations) {
          if (a.getName().toString().equals("Path")) {
            classPath = getMemberValue(a);
            restapi = true;
            return;
          }
        }
      }
    }

    private boolean isRESTAPI() {
      return restapi;
    }

    private String getClassPath() {
      return classPath;
    }
  }

  private static Map<String, String> typeMap;

  static {
    typeMap = new HashMap<>();
    typeMap.put("String", "type: string");
    typeMap.put("boolean", "type: boolean");
    typeMap.put("int", "type: integer");
    typeMap.put("long", "type: integer");
    typeMap.put("Map<String, String>", "$ref: \"#/definitions/JavaStringStringMap\"");

  }

  private class ModelClassChecker<A> extends VoidVisitorAdapter<A> {
    private StringBuilder yaml     = new StringBuilder();
    private List<String>  required = new ArrayList<>();

    private ModelClassChecker() {
      yaml.append("    properties:\n");
    }

    @Override
    public void visit(final FieldDeclaration n, final Object arg) {

      boolean req = false;
      boolean xmlelem = false;
      String example = "";
      NodeList<AnnotationExpr> as = n.getAnnotations();
      for (AnnotationExpr a : as) {
        if (a.getName().toString().equals("RESTExample")) {
          example = unquoteExample(getMemberValue(a, false));
        } else if (a.getName().toString().equals("XmlElement")) {
          xmlelem = true;
          if (a.getClass().equals(NormalAnnotationExpr.class)) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) a).getPairs()) {
              if (pair.getName().toString().equals("required")) {
                if (pair.getValue().toString().equals("true")) {
                  req = true;
                }
              }
            }
          }
        }
      }

      if (!xmlelem)
        return;

      for (final VariableDeclarator var : n.getVariables()) {

        if (req) {
          required.add(var.getName().toString());
        }
        yaml.append("      ").append(var.getName()).append(":\n");
        yaml.append("        ").append(typeMap.get(var.getType().toString())).append("\n");
        // yaml.append(" format: int64\n");
        if (var.hasJavaDocComment()) {
          String cmt = javaDoc2Line(var.getComment().toString());
          yaml.append("        description: ").append(cmt).append("\n");
          if (!example.isEmpty()) {
            yaml.append("        example: ").append(example).append("\n");
          }
        }
      }
    }
  }

  private class RESTAPIMethodLister<A> extends VoidVisitorAdapter<A> {
    private String                       path;
    private Map<String, List<RESTEntry>> entries;

    RESTAPIMethodLister(String path, Map<String, List<RESTEntry>> entries) {
      this.path = path;
      this.entries = entries;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.github.javaparser.ast.visitor.VoidVisitorAdapter#visit(com.github.javaparser.ast.body.MethodDeclaration,
     * java.lang.Object)
     */
    @Override
    public void visit(MethodDeclaration n, Object arg) {
      RESTEntry e = new RESTEntry();
      e.tag = path.substring(1);

      boolean restmethod = false;
      boolean publicAPI = false;
      if (n.getAnnotations() != null) {
        for (AnnotationExpr a : n.getAnnotations()) {
          switch (a.getName().toString()) {
            case "Path":
              e.fullPath = path + getMemberValue(a);
              restmethod = true;
              break;
            case "PublicApi":
              publicAPI = true;
              break;
            case "POST":
              e.method = "post";
              break;
            case "GET":
              e.method = "get";
              break;
            case "PUT":
              e.method = "put";
              break;
            case "DELETE":
              e.method = "delete";
              break;
          }
          if (a.getClass().equals(NormalAnnotationExpr.class)) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) a).getPairs()) {
              if (pair.getName().toString().equals("groups"))
                System.out.println("Group:\"" + pair.getValue() + "\"");
            }
          }
        }
      }

      if (!restmethod || !publicAPI) {
        return;
      }
      boolean anyfound;

      Map<String, String> paramJDoc = new HashMap<>();

      if (n.getJavaDoc() == null) {
        e.responses = "      responses:\n        default:\n          description: Nothing specified in Javadoc\n";
        e.summary = "      summary: No Javadoc found\n";
        e.description = "";
      } else {
        String jdoc = n.getJavaDoc().getContent();

        int ad = jdoc.indexOf("@");
        if (ad == -1) {
          ad = jdoc.length();
        }
        String description = javaDoc2Line(jdoc.substring(0, ad));
        if (description.isEmpty()) {
          e.summary = "      summary: \"\"\n";
          e.description = "      description: \"\"\n";
        } else {
          int dot = description.indexOf(".");
          if (dot == -1) {
            e.summary = "      summary: " + description + "\n";
            e.description = "      description: \"\"\n";
          } else {
            e.summary = "      summary: " + description.substring(0, dot) + "\n";
            description = description.substring(dot + 1);
            if (description.isEmpty()) {
              description = "\"\"";
            }
            e.description = "      description: " + description + "\n";
          }
        }

        String pattern = "@response.representation.";
        int end = 0;
        int start;
        anyfound = false;
        StringBuilder responses = new StringBuilder("      responses:\n");
        while ((start = jdoc.indexOf(pattern, end)) != -1) {
          end = jdoc.indexOf("@", start + 1);
          if (end == -1) {
            end = jdoc.length();
          }
          int dot = jdoc.indexOf(".", start + pattern.length());
          int space = jdoc.indexOf(" ", start + pattern.length());
          String type = jdoc.substring(dot + 1, space);
          if (type.equals("doc")) {

            responses.append("        \"").append(jdoc.substring(start + pattern.length(), dot)).append("\":\n");
            String desc = javaDoc2Line(jdoc.substring(space, end));
            responses.append("          description: ").append(desc).append("\n");
            // TODO parse description and replace any newline followed by a "*" with intended text
            anyfound = true;
          } else if (type.equals("model")) {
            responses.append("          schema:\n");
            String model = javaDoc2Line(jdoc.substring(space, end));
            if (!models.contains(model)) {
              models.add(model);
            }
            responses.append("            $ref: \"#/definitions/").append(model).append("\"\n");
          }
        }
        if (!anyfound) {
          responses.append("        default:\n          description: Nothing specified in Javadoc\n");
        }
        e.responses = responses.toString();

        pattern = "@param ";
        end = 0;
        while ((start = jdoc.indexOf(pattern, end)) != -1) {
          end = jdoc.indexOf("@", start + 1);
          if (end == -1) {
            end = jdoc.length();
          }
          int space = jdoc.indexOf(" ", start + pattern.length());
          String paramName = jdoc.substring(start + pattern.length(), space).trim();
          String desc = javaDoc2Line(jdoc.substring(space + 1, end));
          paramJDoc.put(paramName, desc);
        }
      }

      StringBuilder params = new StringBuilder( "      parameters:\n");
      anyfound = false;
      for (Parameter p : n.getParameters()) {
        String intype = "body";
        String name = p.getName().toString();
        for (AnnotationExpr a : p.getAnnotations()) {

          if (a.getName().toString().equals("PathParam")) {
            intype = "path";
            anyfound = true;
            name = getMemberValue(a);
          } else if (a.getName().toString().equals("QueryParam")) {
            intype = "query";
            name = getMemberValue(a);
            anyfound = true;
          }
        }

        params.append("        - in: ").append(intype).append("\n          name: ").append(name).append("\n");
        String desc = paramJDoc.get(p.getName().toString());
        if (desc == null) {
          desc = "No @param tag found for this parameter";
        }
        desc = javaDoc2Line(desc);
        params.append("          description: ").append(desc).append("\n").append("          required: true\n");
        String simpleType = typeMap.get(p.getType().toString());
        if (simpleType != null && simpleType.startsWith("type:")) {
          if (intype.equals("body")) {
            params.append("          schema:\n").append("            ").append(simpleType).append("\n");
          } else {
            params.append("          ").append(simpleType).append("\n");
          }
        } else {
          params.append("          schema:\n");
          if (simpleType != null) {
            params.append("            ").append(simpleType).append("\n");
          } else {
            // TODO get FQN of type
            params.append("            $ref: '#/definitions/").append(p.getType().toString()).append("\n");
          }
        }
      }
      if (!anyfound) {
        e.parameters = "";
      } else {
        e.parameters = params.toString();
      }

      List<RESTEntry> entrylist = entries.get(e.fullPath);
      if (entrylist == null) {
        entrylist = new ArrayList<>(10);
      }
      entrylist.add(e);
      entries.put(e.fullPath, entrylist);
    }
  }
}
