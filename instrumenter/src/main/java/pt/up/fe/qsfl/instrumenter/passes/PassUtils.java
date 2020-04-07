package pt.up.fe.qsfl.instrumenter.passes;

import javassist.bytecode.LocalVariableAttribute;

import java.util.TreeMap;

public final class PassUtils {
    private PassUtils() { }

    public static String[] allLocalVars(LocalVariableAttribute lva){
        if (lva == null){ return null; }
        int nrLocalVars = lva.tableLength();
        TreeMap<Integer, String> localVarNames = new TreeMap<Integer, String>();
        for (int i = 0; i < nrLocalVars; i++){
            localVarNames.put(lva.index(i), lva.variableName(i));
        }
        return localVarNames.values().toArray(new String[0]);
    }
}
