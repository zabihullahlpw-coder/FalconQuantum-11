import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;
public class SyntaxCheck {
 public static void main(String[] args)throws Exception {
  List<java.io.File> files=new ArrayList<>();
  try(java.util.stream.Stream<Path> s=Files.walk(Paths.get("src"))){s.filter(p->p.toString().endsWith(".java")).forEach(p->files.add(p.toFile()));}
  JavaCompiler c=ToolProvider.getSystemJavaCompiler();DiagnosticCollector<JavaFileObject>d=new DiagnosticCollector<>();
  StandardJavaFileManager fm=c.getStandardFileManager(d,null,null);
  JavacTask task=(JavacTask)c.getTask(null,fm,d,Arrays.asList("-proc:none","-encoding","UTF-8"),null,fm.getJavaFileObjectsFromFiles(files));task.parse();
  boolean bad=false;for(Diagnostic<?> x:d.getDiagnostics())if(x.getKind()==Diagnostic.Kind.ERROR){System.err.println(x);bad=true;}
  if(bad)System.exit(1);System.out.println("Syntax PASS: "+files.size()+" production Java files (UI requires Android SDK type-check/build)");
 }
}
