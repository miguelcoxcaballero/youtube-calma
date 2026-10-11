package es.calma.tools;
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import java.io.File;
import java.util.regex.Pattern;
public class DexFind {
 public static void main(String[] args) throws Exception {
  var pattern=Pattern.compile(args[1]);
  var container=DexFileFactory.loadDexContainer(new File(args[0]),Opcodes.getDefault());
  for (String entry:container.getDexEntryNames()) for(ClassDef type:container.getEntry(entry).getDexFile().getClasses())
   for(Method method:type.getMethods()) if(method.getImplementation()!=null) {
    if (args.length > 2) {
     boolean match=false;
     var second=Pattern.compile(args[2]);
     for(Instruction ins:method.getImplementation().getInstructions())
      if(ins instanceof ReferenceInstruction && second.matcher(((ReferenceInstruction)ins).getReference().toString()).find()) match=true;
     if(!match) continue;
    }
    int index=0;
    for(Instruction ins:method.getImplementation().getInstructions()) {
     if(ins instanceof ReferenceInstruction && pattern.matcher(((ReferenceInstruction)ins).getReference().toString()).find())
      System.out.println(method+" @"+index+" "+((ReferenceInstruction)ins).getReference());
     index++;
    }
   }
 }
}
