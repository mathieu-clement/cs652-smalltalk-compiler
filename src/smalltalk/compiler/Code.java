package smalltalk.compiler;

import smalltalk.compiler.misc.ByteList;

public class Code extends ByteList { // just an alias
	public static final Code None = new Code();

	public static Code of(short... args) {
		Code bytes = new Code();
		for (short b : args) bytes.add(b);
		return bytes;
	}

	public static Code of (int... args) {
		Code bytes = new Code();
		for (int i : args) {
			bytes.add((short) i);
		}
		return bytes;
	}
	
	public static Code withIntOperand(short operation, int operand) {
        checkBounds(operand);
        return Code.of(operation, 0, 0, 0, operand);
	}

    private static void checkBounds(int operand) {
        if (operand > Byte.MAX_VALUE) throw new IllegalArgumentException("sorry bro, operand is too large for me");
    }

    public static Code withShortOperand(short operation, int operand) {
        checkBounds(operand);
        return Code.of(operation, 0, operand);
    }
    
    public static Code withShortOperands(short operation, int... operands) {
        Code bytes = new Code();
        bytes.add(operation);
        for (int operand : operands) {
            checkBounds(operand);
            bytes.add((short) 0);
            bytes.add((short) operand);
        }
        return bytes;
    }

	public static Code join(Code... chunks) {
		Code bytes = new Code();
		for (Code c : chunks) {
			bytes.join(c);
		}
		return bytes;
	}

	// Support code.join(morecode).join(evenmorecode) chains
	public Code join(Code bytes) {
		if ( this == None ) {
			return bytes;
		}
		if ( bytes == None ) {
			return this;
		}
		for (int i=0; i<bytes.n; i++) {
			add(bytes.elements[i]);
		}
		return this;
	}
}
