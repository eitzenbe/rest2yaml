package et.seleniet.rest2yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

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

  private List<String> models = new ArrayList<String>();

  public REST2YAML(String[] args, int rootfoldernum, String header) throws IOException {
    this.root = new File[rootfoldernum];
    for (int i = 0; i < rootfoldernum; i++) {
      root[i] = new File(args[i]);
    }
    this.header = header;
  }

  public String convert2YAML() {
    System.out.println("Checking folder " + root[0]);
    File currFolder = root[0];
    List<File> folders = new ArrayList<File>();
    for (File file : root) {
      folders.add(file); 
    }
    while (currFolder != null) {
      for (File f : currFolder.listFiles()) {
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
        yaml.append("       - " + e.tag + "\n");
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
        } catch (ParseException | IOException e) {
          e.printStackTrace();
        }
      }
    }

    return yaml.toString();
  }
  
  public File findFile(String fname) {
    File currFolder = root[0];
    List<File> folders = new ArrayList<File>();
    for (File file : root) {
      folders.add(file); 
    }
    while (currFolder != null) {
      for (File f : currFolder.listFiles()) {
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

  Map<String, List<RESTEntry>> entries = new HashMap<String, List<RESTEntry>>();

  public void inspectJavaFile(File pFile)
      throws FileNotFoundException, ParseException, IOException {
    CompilationUnit cu;
    FileInputStream in = new FileInputStream(pFile);
    try {
      cu = JavaParser.parse(in);
    } finally {
      in.close();
    }

    RESTAPIClassChecker classparser = new RESTAPIClassChecker();
    classparser.visit(cu, null);

    if (classparser.isRESTAPI()) {
      RESTAPIMethodLister mthlister = new RESTAPIMethodLister(classparser.getClassPath(), entries);
      mthlister.visit(cu, null);
    }
  }

  public void inspectJavaModelFile(File pFile, StringBuilder yaml)
      throws FileNotFoundException, ParseException, IOException {
    CompilationUnit cu;
    FileInputStream in = new FileInputStream(pFile);
    try {
      cu = JavaParser.parse(in);
    } finally {
      in.close();
    }

    ModelClassChecker classparser = new ModelClassChecker();
    classparser.visit(cu, null);

    if (!classparser.required.isEmpty()) {
      yaml.append("    required:\n");
      for (String reqvar : classparser.required) {
        yaml.append("      - " + reqvar + "\n");
      }
    }
    yaml.append(classparser.yaml);
  }

  private static String getMemberValue(AnnotationExpr a) {
    String str = ((SingleMemberAnnotationExpr) a).getMemberValue().toString();

    if (str.startsWith("\"")) {
      str = str.substring(1);
    }
    if (str.endsWith("\"")) {
      str = str.substring(0, str.length() - 1);
    }
    return str;
  }

  private static String getMemberValue(AnnotationExpr a, boolean trimQuotes) {
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
    line = line.replaceAll("[ ]*\\*", "").replace('\n', ' ').replaceAll("  ", " ").trim();
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
  private class RESTAPIClassChecker extends VoidVisitorAdapter {

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

    public boolean isRESTAPI() {
      return restapi;
    }

    public String getClassPath() {
      return classPath;
    }
  }

  private static Map<String, String> typeMap;

  {
    typeMap = new HashMap<String, String>();
    typeMap.put("String", "type: string");
    typeMap.put("boolean", "type: boolean");
    typeMap.put("int", "type: integer");
    typeMap.put("long", "type: integer");
    typeMap.put("Map<String, String>", "$ref: \"#/definitions/JavaStringStringMap\"");

  }

  private class ModelClassChecker extends VoidVisitorAdapter {
    public StringBuilder yaml     = new StringBuilder();
    public List<String>  required = new ArrayList<String>();

    public ModelClassChecker() {
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
        yaml.append("      " + var.getName() + ":\n");
        yaml.append("        " + typeMap.get(var.getType().toString()) + "\n");
        // yaml.append(" format: int64\n");
        if (var.hasJavaDocComment()) {
          String cmt = javaDoc2Line(var.getComment().toString());
          yaml.append("        description: " + cmt + "\n");
          if (!example.isEmpty()) {
            yaml.append("        example: " + example + "\n");
          }
        }
      }
    }
  }

  private class RESTAPIMethodLister extends VoidVisitorAdapter {
    private String                       path;
    private Map<String, List<RESTEntry>> entries;

    public RESTAPIMethodLister(String path, Map<String, List<RESTEntry>> entries) {
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
              if (pair.getName().equals("groups"))
                System.out.println("Group:\"" + pair.getValue() + "\"");
            }
          }
        }
      }

      if (!restmethod || !publicAPI) {
        return;
      }
      boolean anyfound = false;

      Map<String, String> paramJDoc = new HashMap<String, String>();

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
        int start = 0;
        anyfound = false;
        e.responses = "      responses:\n";
        while ((start = jdoc.indexOf(pattern, end)) != -1) {
          end = jdoc.indexOf("@", start + 1);
          if (end == -1) {
            end = jdoc.length();
          }
          int dot = jdoc.indexOf(".", start + pattern.length());
          int space = jdoc.indexOf(" ", start + pattern.length());
          String type = jdoc.substring(dot + 1, space);
          if (type.equals("doc")) {

            e.responses += "        \"" + jdoc.substring(start + pattern.length(), dot) + "\":\n";
            String desc = javaDoc2Line(jdoc.substring(space, end));
            e.responses += "          description: " + desc + "\n";
            // TODO parse description and replace any newline followed by a "*" with intended text
            anyfound = true;
          } else if (type.equals("model")) {
            e.responses += "          schema:\n";
            String model = javaDoc2Line(jdoc.substring(space, end));
            if (!models.contains(model)) {
              models.add(model);
            }
            e.responses += "            $ref: \"#/definitions/" + model + "\"\n";
          }
        }
        if (!anyfound) {
          e.responses += "        default:\n          description: Nothing specified in Javadoc\n";
        }

        pattern = "@param ";
        end = 0;
        start = 0;
        anyfound = false;
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

      e.parameters = "      parameters:\n";
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

        e.parameters += "        - in: " + intype + "\n          name: " + name + "\n";
        String desc = paramJDoc.get(p.getName().toString());
        if (desc == null) {
          desc = "No @param tag found for this parameter";
        }
        desc = javaDoc2Line(desc);
        e.parameters += "          description: " + desc + "\n";
        e.parameters += "          required: true\n";
        String simpleType = typeMap.get(p.getType().toString());
        if (simpleType != null && simpleType.startsWith("type:")) {
          if (intype.equals("body")) {
            e.parameters += "          schema:\n";
            e.parameters += "            " + simpleType + "\n";
          } else {
            e.parameters += "          " + simpleType + "\n";
          }
        } else {
          e.parameters += "          schema:\n";
          if (simpleType != null) {
            e.parameters += "            " + simpleType + "\n";
          } else {
            // TODO get FQN of type
            e.parameters += "            $ref: '#/definitions/" + p.getType().toString() + "\n";
          }
        }
      }
      if (!anyfound) {
        e.parameters = "";
      }

      List<RESTEntry> entrylist = entries.get(e.fullPath);
      if (entrylist == null) {
        entrylist = new ArrayList<RESTEntry>(10);
      }
      entrylist.add(e);
      entries.put(e.fullPath, entrylist);
    }
  }
}
