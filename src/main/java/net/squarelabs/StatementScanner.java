package net.squarelabs;

import com.thinkaurelius.titan.core.TitanGraph;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;
import jdk.nashorn.internal.ir.*;
import jdk.nashorn.internal.parser.Lexer;
import org.apache.commons.lang.NotImplementedException;

import java.io.File;
import java.util.List;
import java.util.Map;

public class StatementScanner {
    private final TitanGraph graph;
    private final File root;
    private final File file;
    private final File folder;
    private final String path;
    private final Vertex vertex;
    private final Map<String, Vertex> map;

    public StatementScanner(TitanGraph graph, Map<String, Vertex> map, File root, File file) {
        this.graph = graph;
        this.map = map;
        this.root = root;
        this.file = file;
        this.folder = file.getParentFile();

        this.path = StatementScanner.makeRelative(root, file);
        String className = namify(path);
        this.vertex = addOrGet(graph, className);
    }

    public void scan(FunctionNode stmt) throws Exception {
        processFunctionNode(stmt);
    }

    private void processFunctionNode(FunctionNode node)  {
        Block body = node.getBody();
        IdentNode in = node.getIdent();
        List<IdentNode> args = node.getParameters();

        String funcName = in.getName();
        if(!"runScript".equals(funcName) && !funcName.contains(":")) { // Nashhorn wrapper & Anonymous methods
            Vertex funcVert = addOrGet(graph, funcName);
            Edge edge = graph.addEdge(null, this.vertex, funcVert, "declares");
            edge.setProperty("relation", "declares");
        }

        processBlock(body);
        processIdentNode(in);
        for(IdentNode arg : args) {
            processIdentNode(arg);
        }
    }

    private void processBlock(Block block) {
        if(block == null) {
            return;
        }
        List<Statement> statements = block.getStatements();
        for(Statement stmt : statements) {
            processStatement(stmt);
        }
    }

    private void processStatement(Statement stmt) {
        Class<?> clazz = stmt.getClass();
        if(clazz == VarNode.class) {
            processVarNode((VarNode)stmt);
            return;
        }
        if(clazz == ReturnNode.class) {
            processReturnNode((ReturnNode) stmt);
            return;
        }
        if(clazz == IfNode.class) {
            processIfNode((IfNode) stmt);
            return;
        }
        if(clazz == ForNode.class) {
            processForNode((ForNode) stmt);
            return;
        }
        if(clazz == ExpressionStatement.class) {
            processExpressionStatement((ExpressionStatement) stmt);
            return;
        }
        if(clazz == BlockStatement.class) {
            processBlockStatement((BlockStatement) stmt);
            return;
        }
        if(clazz == TryNode.class) {
            processTryNode((TryNode) stmt);
            return;
        }
        if(clazz == CatchNode.class) {
            processCatchNode((CatchNode) stmt);
            return;
        }
        if(clazz == BreakNode.class) {
            processBreakNode((BreakNode) stmt);
            return;
        }
        if(clazz == WhileNode.class) {
            processWhileNode((WhileNode) stmt);
            return;
        }
        if(clazz == ThrowNode.class) {
            processThrowNode((ThrowNode) stmt);
            return;
        }
        if(clazz == ContinueNode.class) {
            processContinueNode((ContinueNode) stmt);
            return;
        }
        if(clazz == SwitchNode.class) {
            processSwitchNode((SwitchNode) stmt);
            return;
        }

        throw new NotImplementedException();
    }

    private void processSwitchNode(SwitchNode node) {
        Expression exp = node.getExpression();
        List<CaseNode> cases = node.getCases();
        processExpression(exp);
        for(CaseNode cas : cases) {
            processCaseNode(cas);
        }
    }

    private void processCaseNode(CaseNode node) {
        Expression exp = node.getTest();
        Block body = node.getBody();
        processExpression(exp);
        processBlock(body);
    }

    private void processContinueNode(ContinueNode node) {
        IdentNode lbl = node.getLabel();
        processIdentNode(lbl);
    }

    private void processThrowNode(ThrowNode node) {
        Expression exp = node.getExpression();
        processExpression(exp);
    }

    private void processWhileNode(WhileNode node) {
        Expression test = node.getTest();
        Block blk = node.getBody();
        processExpression(test);
        processBlock(blk);
    }

    private void processBreakNode(BreakNode node) {
        IdentNode lbl = node.getLabel();
        processIdentNode(lbl);
    }

    private void processTryNode(TryNode node) {
        Block body = node.getBody();
        List<Block> catcheBlocks = node.getCatchBlocks();
        List<CatchNode> catches = node.getCatches();
        Block fnlly = node.getFinallyBody();

        processBlock(body);
        for(Block blk : catcheBlocks) {
            processBlock(blk);
        }
        for(CatchNode cth : catches) {
            processCatchNode(cth);
        }
        processBlock(fnlly);
    }

    private void processCatchNode(CatchNode node) {
        Block body = node.getBody();
        IdentNode ex = node.getException();
        Expression except = node.getExceptionCondition();

        processBlock(body);
        processIdentNode(ex);
        processExpression(except);
    }

    private void processBlockStatement(BlockStatement stmt) {
        Block blk = stmt.getBlock();
        processBlock(blk);
    }

    private void processForNode(ForNode node) {
        Expression init = node.getInit();
        Expression test = node.getTest();
        Expression mod = node.getModify();
        Block body = node.getBody();
        processExpression(init);
        processExpression(test);
        processExpression(mod);
        processBlock(body);
    }

    private void processIfNode(IfNode node) {
        Expression test = node.getTest();
        Block pass = node.getPass();
        Block fail = node.getFail();
        processExpression(test);
        processBlock(pass);
        processBlock(fail);
    }

    private void processReturnNode(ReturnNode node) {
        Expression exp = node.getExpression();
        processExpression(exp);
    }

    private void processExpressionStatement(ExpressionStatement stmt) {
        Expression exp = stmt.getExpression();
        processExpression(exp);
    }

    // var x = y;
    private void processVarNode(VarNode node) {
        IdentNode in = node.getName();
        Expression exp = node.getInit();

        String varName = in.getName();
        if("self".equals(varName)) { // Nashhorn wrapper & Anonymous methods
            if(exp.getClass() == UnaryNode.class) {
                UnaryNode un = (UnaryNode)exp;
                Expression rhs = un.rhs();
                if(rhs.getClass() == CallNode.class) {
                    CallNode call = (CallNode)rhs;
                    Expression func = call.getFunction();
                    if(func.getClass() == IdentNode.class) {
                        IdentNode ine = (IdentNode)func;
                        String funcName = ine.getName();
                        Vertex funcVert = addOrGet(graph, funcName);
                        Edge edge = graph.addEdge(null, this.vertex, funcVert, "extends");
                        edge.setProperty("relation", "extends");
                    }
                }
            }
        }

        processIdentNode(in);
        processExpression(exp);
    }

    private void processIdentNode(IdentNode node) {
        if(node == null) {
            return;
        }
        String name = node.getName(); // Do something with the variable name if we want
    }

    private void processExpression(Expression exp) {
        if(exp == null) {
            return;
        }
        Class<?> clazz = exp.getClass();
        if(clazz == ObjectNode.class) {
            processObjectNode((ObjectNode) exp);
            return;
        }
        if(clazz == IdentNode.class) {
            processIdentNode((IdentNode) exp);
            return;
        }
        if(clazz == CallNode.class) {
            processCallNode((CallNode) exp);
            return;
        }
        if(clazz == AccessNode.class) {
            processAccessNode((AccessNode) exp);
            return;
        }
        if(exp instanceof LiteralNode) {
            processLiteralNode((LiteralNode) exp);
            return;
        }
        if(clazz == FunctionNode.class) {
            processFunctionNode((FunctionNode) exp);
            return;
        }
        if(clazz == BinaryNode.class) {
            processBinaryNode((BinaryNode) exp);
            return;
        }
        if(clazz == UnaryNode.class) {
            processUnaryNode((UnaryNode) exp);
            return;
        }
        if(clazz == IndexNode.class) {
            processIndexNode((IndexNode) exp);
            return;
        }
        if(clazz == TernaryNode.class) {
            processTernaryNode((TernaryNode) exp);
            return;
        }
        throw new NotImplementedException();
    }

    private void processTernaryNode(TernaryNode node) {
        Expression test = node.getTest();
        Expression tru = node.getTrueExpression();
        Expression fal = node.getFalseExpression();

        processExpression(test);
        processExpression(tru);
        processExpression(fal);
    }

    private void processIndexNode(IndexNode node) {
        Expression base = node.getBase();
        Expression idx = node.getIndex();
        processExpression(base);
        processExpression(idx);
    }

    private void processUnaryNode(UnaryNode node) {
        Expression exp = node.rhs();
        processExpression(exp);
    }

    private void processBinaryNode(BinaryNode node) {
        Expression src = node.rhs();
        Expression dst = node.lhs();
        processExpression(src);
        processExpression(dst);
    }

    private void processAccessNode(AccessNode node) {
        Expression base = node.getBase();
        IdentNode prop = node.getProperty();
        processExpression(base);
        processIdentNode(prop);
    }

    // myFunc(arg1, arg2);
    private void processCallNode(CallNode node) {
        Expression func = node.getFunction();
        List<Expression> exps = node.getArgs();

        if(func instanceof IdentNode) {
            IdentNode in = (IdentNode)func;
            String funcName = in.getName();
            Vertex funcVert = addOrGet(graph, funcName);
            Edge edge = graph.addEdge(null, this.vertex, funcVert, "invokes");
            edge.setProperty("relation", "invokes");
            if("require".equals(funcName) || "define".equals(funcName)) {
                if(exps.size() == 2 && exps.get(0) instanceof LiteralNode.ArrayLiteralNode) { // require([path1, path2], callback) {}
                    LiteralNode.ArrayLiteralNode arg0 = (LiteralNode.ArrayLiteralNode)exps.get(0);
                    Expression[] paths = arg0.getValue();
                    for(Expression pathExp : paths) {
                        if(pathExp instanceof LiteralNode) {
                            LiteralNode ln = (LiteralNode)pathExp;
                            Object obj = ln.getObject();
                            if(obj instanceof String) {
                                String path = ((String)obj);
                                String className = namify(path);
                                Vertex child = addOrGet(graph, className);
                                Edge e = graph.addEdge(null, this.vertex, child, "requires");
                                e.setProperty("relation", "requires");
                            }
                        }
                    }
                }
            }
        }

        processExpression(func);
        for(Expression exp : exps) {
            processExpression(exp);
        }
    }

    private String namify(String path) {
        String[] parts = path.split("/");
        String className = parts[parts.length-1];
        parts = className.split("\\.");
        className = parts[0];
        return className;
    }

    private void processLiteralNode(LiteralNode node) {
        Object val = node.getObject();
        if(val == null) {
            return;
        }
        Class clazz = val.getClass();
        if(clazz == String.class) {
            String str = (String)val;
            return;
        }
        if(clazz == Integer.class) {
            Integer i = (Integer)val;
            return;
        }
        if(clazz == Boolean.class) {
            Boolean b = (Boolean)val;
            return;
        }
        if(clazz == Expression.class) {
            Expression exp = (Expression)val;
            processExpression(exp);
            return;
        }
        if(clazz == Expression[].class) {
            Expression[] exps = (Expression[])val;
            for(Expression exp : exps) {
                processExpression(exp);
            }
            return;
        }
        if(clazz == Lexer.RegexToken.class) {
            processRegexToken((Lexer.RegexToken)val);
            return;
        }
        throw new NotImplementedException();
    }

    private void processRegexToken(Lexer.RegexToken token) {
        String options = token.getOptions();
        String regex = token.getExpression();
    }

    // var x = {y: 1, z: 2}
    private void processObjectNode(ObjectNode node) {
        List<PropertyNode> elems = node.getElements();
        for(PropertyNode pn : elems) {
            processPropertyNode(pn);
        }
    }

    // { key: value }
    private void processPropertyNode(PropertyNode node) {
        Expression key = node.getKey();
        Expression value = node.getValue();
        processExpression(key);
        processExpression(value);
    }

    public static String makeRelative(File root, File child) {
        String path = root.toURI().relativize(child.toURI()).getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private Vertex addOrGet(TitanGraph graph, String path) {
        if (map.containsKey(path)) {
            return map.get(path);
        }
        Vertex vert = graph.addVertex(path);
        vert.setProperty("path", path);
        map.put(path, vert);
        return vert;
    }

}
