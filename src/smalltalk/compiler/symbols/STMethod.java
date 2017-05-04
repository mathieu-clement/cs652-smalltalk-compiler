package smalltalk.compiler.symbols;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.List;

/** A Smalltalk method symbol. It's like a block with a name.
 *
 *  Args and first level locals for a method are in the same scope.
 */
public class STMethod extends STBlock {
	/** Currently set by compiler but not used / needed by VM.
	 *  Class methods are treated no differently than instance methods
	 *  and rely on the programmer to not send a class method to an instance.
	 *  For example, "Array new" makes sense but "x new" for
	 *  some instance X does not. See, e.g., testClassMessageOnInstanceError.
	 */
	public boolean isClassMethod;

	private List<String> fields = new ArrayList<>();

	public STMethod(String name, ParserRuleContext tree) {
		super(name, tree);
	}

	public boolean isMethod() { return true; }

	public int getFieldIndex(String fieldName) {
        return fields.indexOf(fieldName);
    }

    public void addField(String name) {
	    if (fields.contains(name)) {
            throw new IllegalStateException("Class already contains a field with name '" + name + "'");
        }
        fields.add(name);
    }
}
