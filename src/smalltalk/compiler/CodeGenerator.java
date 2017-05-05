package smalltalk.compiler;

import org.antlr.symtab.Scope;
import org.antlr.symtab.Symbol;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import smalltalk.compiler.symbols.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fill STBlock, STMethod objects in Symbol table with bytecode,
 * {@link STCompiledBlock}.
 */
public class CodeGenerator extends SmalltalkBaseVisitor<Code> {
    public static final boolean dumpCode = false;

    public STClass currentClassScope;
    public STMethod currentMethod;
    public Scope currentScope;

    /**
     * With which compiler are we generating code?
     */
    public final Compiler compiler;

    public CodeGenerator(Compiler compiler) {
        this.compiler = compiler;
    }

    /**
     * This and defaultResult() critical to getting code to bubble up the
     * visitor call stack when we don't implement every method.
     */
    @Override
    protected Code aggregateResult(Code aggregate, Code nextResult) {
        if (aggregate != Code.None) {
            if (nextResult != Code.None) {
                return aggregate.join(nextResult);
            }
            return aggregate;
        } else {
            return nextResult;
        }
    }

    @Override
    protected Code defaultResult() {
        return Code.None;
    }

    @Override
    public Code visitFile(SmalltalkParser.FileContext ctx) {
        pushScope(compiler.symtab.GLOBALS);
        visitChildren(ctx);
        popScope();
        return Code.None;
    }

    @Override
    public Code visitMain(SmalltalkParser.MainContext ctx) {
        if (ctx.body() instanceof SmalltalkParser.EmptyBodyContext) {
            return Code.None;
        }

        pushScope(ctx.scope);
        currentMethod = ctx.scope;
        currentClassScope = ctx.classScope;
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        Code code = visit(ctx.body())
                .join(Code.of(Bytecode.POP, Bytecode.SELF, Bytecode.RETURN));
        ctx.scope.compiledBlock.bytecode = code.bytes();
        ctx.scope.compiledBlock.setNargs(ctx.scope.getNumberOfParameters());
        ctx.scope.compiledBlock.setNlocals(ctx.scope.getNumberOfVariables());
        popScope();
        currentMethod = null;
        return code;
    }

    @Override
    public Code visitLocalVars(SmalltalkParser.LocalVarsContext ctx) {
        ctx.ID().stream()
                .map(ParseTree::getText)
                .forEach(currentMethod::addLocalVariable);
        return Code.None;
    }

    @Override
    public Code visitAssign(SmalltalkParser.AssignContext ctx) {
        Code code = visit(ctx.messageExpression());
        String varName = ctx.lvalue().sym.getName();
        int localIndex = currentMethod.getLocalIndex(varName);
        if (localIndex != -1) {
            int delta = currentMethod.getRelativeScopeCount(varName);
            return code.join(Code.withShortOperands(Bytecode.STORE_LOCAL, delta, localIndex));
        } else {
            int delta = currentMethod.getRelativeScopeCount(varName);
            int fieldIndex = currentClassScope.getFieldIndex(varName);
            return code.join(Code.of(Bytecode.STORE_FIELD, delta, fieldIndex));
        }
    }

    @Override
    public Code visitInstanceVars(SmalltalkParser.InstanceVarsContext ctx) {
        return Code.None;
    }

    @Override
    public Code visitBlock(SmalltalkParser.BlockContext ctx) {
        pushScope(ctx.scope);
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        addBlockToCurrentMethod(ctx.scope.compiledBlock);
        Code code = Code.None;
        if (ctx.blockArgs() != null) {
            visit(ctx.blockArgs());
        }
        code = code.join(visit(ctx.body()));
        code = code.join(Code.of(Bytecode.BLOCK_RETURN));

        ctx.scope.compiledBlock.bytecode = code.bytes();
        ctx.scope.compiledBlock.setNargs(ctx.scope.getNumberOfParameters());
        ctx.scope.compiledBlock.setNlocals(ctx.scope.getNumberOfVariables() - ctx.scope.getNumberOfParameters());
        int blockIndex = ctx.scope.index;
        popScope();

        return Code.withShortOperand(Bytecode.BLOCK, blockIndex);
    }

    private void addBlockToCurrentMethod(STCompiledBlock childBlock) {
        STCompiledBlock[] parentBlocks = currentMethod.compiledBlock.blocks;
        if (parentBlocks == null) {
            currentMethod.compiledBlock.blocks = new STCompiledBlock[1];
            currentMethod.compiledBlock.blocks[0] = childBlock;
        } else {
            STCompiledBlock[] newBlocks = Arrays.copyOf(parentBlocks, parentBlocks.length + 1);
            currentMethod.compiledBlock.blocks = newBlocks;
            newBlocks[newBlocks.length - 1] = childBlock;
        }
    }

    @Override
    public Code visitBlockArgs(SmalltalkParser.BlockArgsContext ctx) {
        for (TerminalNode idNode : ctx.ID()) {
            currentMethod.addArgument(idNode.getText());
        }
        return Code.None;
    }

    @Override
    public Code visitSmalltalkMethodBlock(SmalltalkParser.SmalltalkMethodBlockContext ctx) {
        return visit(ctx.body());
    }

    @Override
    public Code visitPrimitiveMethodBlock(SmalltalkParser.PrimitiveMethodBlockContext ctx) {
        return Code.None;
    }

    @Override
    public Code visitOperatorMethod(SmalltalkParser.OperatorMethodContext ctx) {
        pushScope(ctx.scope);
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        if (ctx.methodBlock() instanceof SmalltalkParser.PrimitiveMethodBlockContext) {
            ctx.scope.compiledBlock.bytecode = new byte[0];
            ctx.scope.compiledBlock.setNlocals(0);
            ctx.scope.compiledBlock.setNargs(ctx.scope.getNumberOfParameters());
        } else {
            ctx.scope.compiledBlock.bytecode = visit(ctx.methodBlock())
                    .join(Code.of(Bytecode.POP, Bytecode.SELF, Bytecode.RETURN))
                    .bytes();
        }
        popScope();
        return Code.None;
    }

    @Override
    public Code visitKeywordMethod(SmalltalkParser.KeywordMethodContext ctx) {
        pushScope(ctx.scope);
        currentMethod = ctx.scope;
//        for (TerminalNode keywordNode : ctx.KEYWORD()) {
//            String keyword = keywordNode.getText();
//            addLiteral(keyword);
//        }
        for (TerminalNode idNode : ctx.ID()) {
            String arg = idNode.getText();
            currentMethod.addArgument(arg);
        }
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        ctx.scope.compiledBlock.bytecode = visit(ctx.methodBlock())
                .join(Code.of(Bytecode.POP, Bytecode.SELF, Bytecode.RETURN))
                .bytes();
        popScope();
        currentMethod = null;
        return Code.None;
    }

    @Override
    public Code visitClassMethod(SmalltalkParser.ClassMethodContext ctx) {
        SmalltalkParser.MethodContext methodContext = ctx.method();
        currentMethod = ctx.method().scope;
        methodContext.scope.isClassMethod = true;
        visit(methodContext);
        currentMethod = null;
        return Code.None;
    }

    @Override
    public Code visitNamedMethod(SmalltalkParser.NamedMethodContext ctx) {
        currentMethod = ctx.scope;
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, ctx.scope);
        Code methodBlockCode = visit(ctx.methodBlock());
        if (ctx.methodBlock() instanceof SmalltalkParser.SmalltalkMethodBlockContext) {
            if (((SmalltalkParser.SmalltalkMethodBlockContext) ctx.methodBlock()).body() instanceof SmalltalkParser.EmptyBodyContext) {
                ctx.scope.compiledBlock.bytecode = Code.of(Bytecode.SELF, Bytecode.RETURN).bytes();
            } else {
                ctx.scope.compiledBlock.bytecode = methodBlockCode
                        .join(Code.of(Bytecode.POP, Bytecode.SELF, Bytecode.RETURN))
                        .bytes();
            }
        } else if (ctx.methodBlock() instanceof SmalltalkParser.PrimitiveMethodBlockContext) {
            ctx.scope.compiledBlock.bytecode = new byte[0];
        }

        currentMethod = null;
        return Code.None;
    }

    @Override
    public Code visitClassDef(SmalltalkParser.ClassDefContext ctx) {
        currentClassScope = ctx.scope;
        pushScope(ctx.scope);
        Code code = visitChildren(ctx);
        popScope();
        currentClassScope = null;
        return code;
    }

    public STCompiledBlock getCompiledPrimitive(STPrimitiveMethod primitive) {
        STCompiledBlock compiledMethod = new STCompiledBlock(currentClassScope, primitive);
        return compiledMethod;
    }

    /**
     * All expressions have values. Must pop each expression value off, except
     * last one, which is the block return value. Visit method for blocks will
     * issue block_return instruction. Visit method for method will issue
     * pop self return.  If last expression is ^expr, the block_return or
     * pop self return is dead code but it is always there as a failsafe.
     * <p>
     * localVars? expr ('.' expr)* '.'?
     */
    @Override
    public Code visitFullBody(SmalltalkParser.FullBodyContext ctx) {
        Code code = Code.None;
        if (ctx.localVars() != null) {
            code = code.join(visit(ctx.localVars()));
        }
        for (int i = 0; i < ctx.stat().size(); i++) {
            if (i != 0) {
                code = code.join(Code.of(Bytecode.POP));
            }
            Code statCode = visit(ctx.stat(i));
            code = code.join(statCode);
        }
        return code;
    }

    @Override
    public Code visitEmptyBody(SmalltalkParser.EmptyBodyContext ctx) {
        return Code.of(Bytecode.NIL);
    }

    @Override
    public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
        Code e = visit(ctx.messageExpression());
        if (compiler.genDbg) {
            e = Code.join(e, dbg(ctx.start)); // put dbg after expression as that is when it executes
        }
        return e.join(Compiler.method_return());
    }

    public void pushScope(Scope scope) {
        currentScope = scope;
    }

    public void popScope() {
//		if ( currentScope.getEnclosingScope()!=null ) {
//			System.out.println("popping from " + currentScope.getScopeName() + " to " + currentScope.getEnclosingScope().getScopeName());
//		}
//		else {
//			System.out.println("popping from " + currentScope.getScopeName() + " to null");
//		}
        currentScope = currentScope.getEnclosingScope();
    }

    @Override
    public Code visitId(SmalltalkParser.IdContext ctx) {
        String id = ctx.getText();
        Symbol symbol = currentScope.resolve(id);
        if (currentMethod.getLocalIndex(id) != -1) {
            return Code.withIntOperand(Bytecode.PUSH_LOCAL, currentMethod.getLocalIndex(id));
        } else if (currentClassScope.getFieldIndex(id) != -1) {
            return Code.withShortOperand(Bytecode.PUSH_FIELD, currentClassScope.getFieldIndex(id));
        } else if (currentMethod.getArgumentIndex(id) != -1) {
            return Code.withIntOperand(Bytecode.PUSH_LOCAL, currentMethod.getArgumentIndex(id));
        } else {
            int idx = currentClassScope.stringTable.add(id);
            return Code.withShortOperand(Bytecode.PUSH_GLOBAL, idx);
        }
    }

    public int getLiteralIndex(String s) {
        return currentClassScope.stringTable.toList().indexOf(s);
    }

    @Override
    public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
        if (ctx.STRING() != null) {
            String str = ctx.STRING().getText();
            str = str.replaceAll("'", "");
            addLiteral(str);
            int literalIndex = getLiteralIndex(str);
            return Code.withShortOperand(Bytecode.PUSH_LITERAL, literalIndex);
        }
        if (ctx.CHAR() != null) {
            char c = ctx.CHAR().getText().charAt(0);
            return Code.of(Bytecode.PUSH_CHAR, c);
        }
        if (ctx.NUMBER() != null) {
            String intStr = ctx.NUMBER().getText();
            short s = Short.parseShort(intStr);
            return Code.withIntOperand(Bytecode.PUSH_INT, s);
        }
        String literal = ctx.getText();
        if (!literalBytecodes.containsKey(literal)) {
            throw new RuntimeException("Unknown literal: " + literal);
        }
        return Code.of(literalBytecodes.get(literal));
    }

    private static final Map<String, Short> literalBytecodes = new HashMap<String, Short>() {{
        put("nil", Bytecode.NIL);
        put("self", Bytecode.SELF);
        put("true", Bytecode.TRUE);
        put("false", Bytecode.FALSE);
    }};

    public void addLiteral(String id) {
        currentClassScope.stringTable.add(id);
    }

    public Code dbgAtEndMain(Token t) {
        int charPos = t.getCharPositionInLine() + t.getText().length();
        return dbg(t.getLine(), charPos);
    }


    public Code dbgAtEndBlock(Token t) {
        int charPos = t.getCharPositionInLine() + t.getText().length();
        charPos -= 1; // point at ']'
        return dbg(t.getLine(), charPos);
    }

    public Code dbg(Token t) {
        return dbg(t.getLine(), t.getCharPositionInLine());
    }

    public Code dbg(int line, int charPos) {
        return Compiler.dbg(getLiteralIndex(compiler.getFileName()), line, charPos);
    }

    public Code store(String id) {
        return null;
    }

    public Code push(String id) {
        return null;
    }

    @Override
    public Code visitSendMessage(SmalltalkParser.SendMessageContext ctx) {
        return visit(ctx.messageExpression());
    }

    public Code sendKeywordMsg(ParserRuleContext receiver,
                               Code receiverCode,
                               List<SmalltalkParser.BinaryExpressionContext> args,
                               List<TerminalNode> keywords) {


        Code code = new Code();
        code = code.join(receiverCode);
        for (SmalltalkParser.BinaryExpressionContext arg : args) {
            code = code.join(visit(arg));
        }
        StringBuilder sb = new StringBuilder();
        for (TerminalNode keyword : keywords) {
            sb.append(keyword.getText());
        }
        String literal = sb.toString();
        addLiteral(literal);

        int receiverIndex = getLiteralIndex(literal);
        code = code.join(Code.withShortOperands(Bytecode.SEND, keywords.size(), receiverIndex));
        return code;
    }

    @Override
    public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
        Code recvCode = visit(ctx.recv);
        return sendKeywordMsg(ctx.recv, recvCode, ctx.args, ctx.KEYWORD());
    }

    @Override
    public Code visitSuperKeywordSend(SmalltalkParser.SuperKeywordSendContext ctx) {
        Code recvCode = visit(ctx.binaryExpression);
        return sendKeywordMsg(ctx.binaryExpression, recvCode, ctx.args, ctx.KEYWORD());
    }

    @Override
    public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
        Code code = Code.None;
        // Load 2 expressions, then apply binary operator
        // Rinse and repeat
        code = code.join(visit(ctx.unaryExpression(0)));
        int j = 1;
        for (int i = 0; i < ctx.bop().size(); i++) {
            code = code.join(visit(ctx.unaryExpression(j++)));
            code = code.join(visit(ctx.bop(i)));
        }
        return code;
    }

    @Override
    public Code visitUnaryMsgSend(SmalltalkParser.UnaryMsgSendContext ctx) {
        String literal = ctx.ID().getText();
        addLiteral(literal);
        return Code.withShortOperands(Bytecode.SEND, 0, getLiteralIndex(literal));
    }

    @Override
    public Code visitBop(SmalltalkParser.BopContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (SmalltalkParser.OpcharContext opcharContext : ctx.opchar()) {
            sb.append(opcharContext.getText());
        }
        String op = sb.toString();
        addLiteral(op);
        return Code.withShortOperands(Bytecode.SEND, 1, getLiteralIndex(op));
    }

    public String getProgramSourceForSubtree(ParserRuleContext ctx) {
        return ctx.toStringTree();
    }

    /*
    To parse: '^' expr

    Code c = visit(ctx.expr());
    return c.join(Compiler.method_return());
     */
}
