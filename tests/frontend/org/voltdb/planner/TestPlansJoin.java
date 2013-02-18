/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.planner;

import java.util.List;

import junit.framework.TestCase;

import org.voltdb.expressions.AbstractExpression;
import org.voltdb.plannodes.*;
import org.voltdb.types.ExpressionType;
import org.voltdb.types.IndexLookupType;
import org.voltdb.types.JoinType;
import org.voltdb.types.PlanNodeType;

public class TestPlansJoin extends TestCase {
    private PlannerTestAideDeCamp aide;

    private AbstractPlanNode compile(String sql, int paramCount,
                                     boolean singlePartition,
                                     String joinOrder)
    {
        List<AbstractPlanNode> pn = null;
        try {
            pn =  aide.compile(sql, paramCount, singlePartition, joinOrder);
        }
        catch (NullPointerException ex) {
            // aide may throw NPE if no plangraph was created
            ex.printStackTrace();
            fail();
        }
        catch (Exception ex) {
            ex.printStackTrace();
            fail();
        }
        assertTrue(pn != null);
        assertFalse(pn.isEmpty());
        assertTrue(pn.get(0) != null);
        return pn.get(0);
    }

    public void testBasicInnerJoin() {
        // select * with ON clause should return all columns from all tables
        AbstractPlanNode pn = compile("select * FROM R1 JOIN R2 ON R1.C = R2.C", 0, false, null);
        AbstractPlanNode n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopPlanNode);
        NestLoopPlanNode nlj = (NestLoopPlanNode) n;
        //assertTrue(nlj.getJoinType() == JoinType.INNER);
        for (int ii = 0; ii < 2; ii++) {
            assertTrue(n.getChild(ii) instanceof SeqScanPlanNode);
        }
        assertTrue(pn.getOutputSchema().getColumns().size() == 5);

        // select * with USING clause should contain only one column for each column from the USING expression
        pn = compile("select * FROM R1 JOIN R2 USING(C)", 0, false, null);
        assertTrue(n instanceof NestLoopPlanNode);
        assertTrue(pn.getOutputSchema().getColumns().size() == 4);

        pn = compile("select A,C,D FROM R1 JOIN R2 ON R1.C = R2.C", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopPlanNode);
        assertTrue(pn.getOutputSchema().getColumns().size() == 3);

        pn = compile("select A,C,D FROM R1 JOIN R2 USING(C)", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopPlanNode);
        assertTrue(pn.getOutputSchema().getColumns().size() == 3);

        pn = compile("select R1.A, R2.C, R1.D FROM R1 JOIN R2 ON R1.C = R2.C", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopPlanNode);
        assertTrue(pn.getOutputSchema().getColumns().size() == 3);
        assertTrue(pn.getOutputSchema().getColumns().get(0).getTableName().equalsIgnoreCase("R1"));
        assertTrue(pn.getOutputSchema().getColumns().get(1).getTableName().equalsIgnoreCase("R2"));

        // The output table for C canbe either R1 or R2 because it's an INNER join
        pn = compile("select R1.A, C, R1.D FROM R1 JOIN R2 USING(C)", 0, false, null);
        n = pn.getChild(0).getChild(0);
        String table = pn.getOutputSchema().getColumns().get(1).getTableName();
        assertTrue(n instanceof NestLoopPlanNode);
        assertTrue(pn.getOutputSchema().getColumns().size() == 3);
        assertTrue(pn.getOutputSchema().getColumns().get(0).getTableName().equalsIgnoreCase("R1"));
        assertTrue(table.equalsIgnoreCase("R2") || table.equalsIgnoreCase("R1"));

        // Column from USING expression can not have qualifier in the SELECT clause
        try {
            List<AbstractPlanNode> pnl = aide.compile("select R1.C FROM R1 JOIN R2 USING(C)", 0, false, null);
            fail();
        } catch (PlanningErrorException ex) {
            assertTrue("user lacks privilege or object not found: R1.C".equalsIgnoreCase(ex.getMessage()));
        }
        try {
            List<AbstractPlanNode> pnl = aide.compile("select R2.C FROM R1 JOIN R2 USING(C)", 0, false, null);
            fail();
        } catch (PlanningErrorException ex) {
            assertTrue("user lacks privilege or object not found: R2.C".equalsIgnoreCase(ex.getMessage()));
        }

        try {
            List<AbstractPlanNode> pnl = aide.compile("select R2.C FROM R1 JOIN R2 USING(X)", 0, false, null);
            fail();
        } catch (PlanningErrorException ex) {
            assertTrue("user lacks privilege or object not found: X".equalsIgnoreCase(ex.getMessage()));
        }
        try {
            List<AbstractPlanNode> pnl = aide.compile("select R2.C FROM R1 JOIN R2 ON R1.X = R2.X", 0, false, null);
            fail();
        } catch (PlanningErrorException ex) {
            assertTrue("user lacks privilege or object not found: R1.X".equalsIgnoreCase(ex.getMessage()));
        }
    }

    public void testBasicThreeTableInnerJoin() {
        AbstractPlanNode pn = compile("select * FROM R1 JOIN R2 ON R1.C = R2.C JOIN R3 ON R3.C = R2.C", 0, false, null);
        AbstractPlanNode n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopPlanNode);
        assertTrue(n.getChild(0) instanceof SeqScanPlanNode);
        assertTrue(n.getChild(1) instanceof NestLoopPlanNode);
        assertTrue(pn.getOutputSchema().getColumns().size() == 7);

        pn = compile("select R1.C, R2.C R3.C FROM R1 INNER JOIN R2 ON R1.C = R2.C INNER JOIN R3 ON R3.C = R2.C", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopPlanNode);
        assertTrue(n.getChild(0) instanceof SeqScanPlanNode);
        assertTrue(n.getChild(1) instanceof NestLoopPlanNode);

        pn = compile("select C FROM R1 INNER JOIN R2 USING (C) INNER JOIN R3 USING(C)", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopPlanNode);
        assertTrue(n.getChild(0) instanceof SeqScanPlanNode);
        assertTrue(n.getChild(1) instanceof NestLoopPlanNode);
        assertTrue(pn.getOutputSchema().getColumns().size() == 1);

        pn = compile("select C FROM R1 INNER JOIN R2 USING (C), R3 WHERE R1.A = R3.A", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopPlanNode);
        assertTrue(n.getChild(0) instanceof SeqScanPlanNode);
        assertTrue(n.getChild(1) instanceof NestLoopIndexPlanNode);
        assertTrue(pn.getOutputSchema().getColumns().size() == 1);

        try {
            List<AbstractPlanNode> pnl = aide.compile("select C, R3.C FROM R1 INNER JOIN R2 USING (C) INNER JOIN R3 ON R1.C = R3.C", 0, false, null);
            fail();
        } catch (PlanningErrorException ex) {
            assertTrue("user lacks privilege or object not found: R1.C".equalsIgnoreCase(ex.getMessage()));
        }

    }

    public void testScanJoinConditions() {
        AbstractPlanNode pn = compile("select * FROM R1 WHERE R1.C = 0", 0, false, null);
        AbstractPlanNode n = pn.getChild(0);
        assertTrue(n instanceof AbstractScanPlanNode);
        AbstractScanPlanNode scan = (AbstractScanPlanNode) n;
        AbstractExpression p = scan.getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_EQUAL);

        pn = compile("select * FROM R1, R2 WHERE R1.A = R2.A AND R1.C > 0", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof AbstractJoinPlanNode);
        p = ((AbstractJoinPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_EQUAL);
        n = n.getChild(0);
        assertTrue(n instanceof AbstractScanPlanNode);
        assertTrue(((AbstractScanPlanNode) n).getTargetTableName().equalsIgnoreCase("R1"));
        p = ((AbstractScanPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_GREATERTHAN);

        pn = compile("select * FROM R1, R2 WHERE R1.A = R2.A AND R1.C > R2.C", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof AbstractJoinPlanNode);
        p = ((AbstractJoinPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.CONJUNCTION_AND);
        assertTrue(p.getLeft().getExpressionType() == ExpressionType.COMPARE_LESSTHAN);
        assertTrue(p.getRight().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        assertTrue(((AbstractScanPlanNode)n.getChild(0)).getPredicate() == null);
        assertTrue(((AbstractScanPlanNode)n.getChild(1)).getPredicate() == null);

        pn = compile("select * FROM R1 JOIN R2 ON R1.A = R2.A WHERE R1.C > 0", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof AbstractJoinPlanNode);
        p = ((AbstractJoinPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_EQUAL);
        n = n.getChild(0);
        assertTrue(n instanceof AbstractScanPlanNode);
        assertTrue(((AbstractScanPlanNode) n).getTargetTableName().equalsIgnoreCase("R1"));
        p = ((AbstractScanPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_GREATERTHAN);

        pn = compile("select * FROM R1 JOIN R2 ON R1.A = R2.A WHERE R1.C > R2.C", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof AbstractJoinPlanNode);
        p = ((AbstractJoinPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.CONJUNCTION_AND);
        assertTrue(p.getLeft().getExpressionType() == ExpressionType.COMPARE_LESSTHAN);
        assertTrue(p.getRight().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        assertTrue(((AbstractScanPlanNode)n.getChild(0)).getPredicate() == null);
        assertTrue(((AbstractScanPlanNode)n.getChild(1)).getPredicate() == null);

        pn = compile("select * FROM R1, R2, R3 WHERE R1.A = R2.A AND R1.C = R3.C AND R1.A > 0", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof AbstractJoinPlanNode);
        p = ((AbstractJoinPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.CONJUNCTION_AND);
        assertTrue(p.getLeft().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        assertTrue(p.getRight().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        n = n.getChild(0);
        assertTrue(n instanceof AbstractScanPlanNode);
        assertTrue(((AbstractScanPlanNode) n).getTargetTableName().equalsIgnoreCase("R1"));
        p = ((AbstractScanPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_GREATERTHAN);

        pn = compile("select * FROM R1 JOIN R2 ON R1.A = R2.A JOIN R3 ON R1.C = R3.C WHERE R1.A > 0", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof AbstractJoinPlanNode);
        p = ((AbstractJoinPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.CONJUNCTION_AND);
        assertTrue(p.getLeft().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        assertTrue(p.getRight().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        n = n.getChild(0);
        assertTrue(n instanceof AbstractScanPlanNode);
        assertTrue(((AbstractScanPlanNode) n).getTargetTableName().equalsIgnoreCase("R1"));
        p = ((AbstractScanPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_GREATERTHAN);
}

    public void testFunctionJoinConditions() {
        AbstractPlanNode pn = compile("select * FROM R1 JOIN R2 ON ABS(R1.A) = ABS(R2.A) ", 0, false, null);
        AbstractPlanNode n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof AbstractJoinPlanNode);
        AbstractExpression p = ((AbstractJoinPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_EQUAL);
        assertTrue(p.getLeft().getExpressionType() == ExpressionType.FUNCTION);
        assertTrue(p.getRight().getExpressionType() == ExpressionType.FUNCTION);

        pn = compile("select * FROM R1 ,R2 WHERE ABS(R1.A) = ABS(R2.A) ", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof AbstractJoinPlanNode);
        p = ((AbstractJoinPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_EQUAL);
        assertTrue(p.getLeft().getExpressionType() == ExpressionType.FUNCTION);
        assertTrue(p.getRight().getExpressionType() == ExpressionType.FUNCTION);

        // USING expression can have only comma separated list of column names
        try {
            List<AbstractPlanNode> pnl = aide.compile("select * FROM R1 JOIN R2 USING (ABS(A))", 0, false, null);
            fail();
        } catch (PlanningErrorException ex) {
            System.out.println(ex.getMessage());
            assertTrue("user lacks privilege or object not found: ABS".equalsIgnoreCase(ex.getMessage()));
        }
    }

    public void testIndexJoinConditions() {
        AbstractPlanNode pn = compile("select * FROM R3 WHERE R3.A = 0", 0, false, null);
        AbstractPlanNode n = pn.getChild(0);
        assertTrue(n instanceof IndexScanPlanNode);
        assertTrue(((IndexScanPlanNode) n).getPredicate() == null);

        pn = compile("select * FROM R3 WHERE R3.A > 0 and R3.A < 5 and R3.C = 4", 0, false, null);
        n = pn.getChild(0);
        assertTrue(n instanceof IndexScanPlanNode);
        IndexScanPlanNode indexScan = (IndexScanPlanNode) n;
        AbstractExpression p = indexScan.getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_EQUAL);
        p = indexScan.getEndExpression();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_LESSTHAN);
        assertTrue(indexScan.getLookupType().equals(IndexLookupType.GT));

        pn = compile("select * FROM R3, R2 WHERE R3.A = R2.A AND R3.C > 0 and R2.C >= 5", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopIndexPlanNode);
        assertTrue(((NestLoopIndexPlanNode) n).getPredicate() == null);
        indexScan = (IndexScanPlanNode)n.getInlinePlanNode(PlanNodeType.INDEXSCAN);
        assertTrue(indexScan.getLookupType().equals(IndexLookupType.EQ));
        assertTrue(indexScan.getEndExpression().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        AbstractPlanNode seqScan = (SeqScanPlanNode) n.getChild(0);
        assertTrue(seqScan instanceof SeqScanPlanNode);
        assertTrue(((SeqScanPlanNode)seqScan).getPredicate().getExpressionType().equals(ExpressionType.COMPARE_GREATERTHANOREQUALTO));

        pn = compile("select * FROM R3 JOIN R2 ON R3.A = R2.A WHERE R3.C > 0 and R2.C >= 5", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopIndexPlanNode);
        assertTrue(((NestLoopIndexPlanNode) n).getPredicate() == null);
        indexScan = (IndexScanPlanNode)n.getInlinePlanNode(PlanNodeType.INDEXSCAN);
        assertTrue(indexScan.getLookupType().equals(IndexLookupType.EQ));
        assertTrue(indexScan.getEndExpression().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        seqScan = (SeqScanPlanNode) n.getChild(0);
        assertTrue(seqScan instanceof SeqScanPlanNode);
        assertTrue(((SeqScanPlanNode)seqScan).getPredicate().getExpressionType().equals(ExpressionType.COMPARE_GREATERTHANOREQUALTO));

        pn = compile("select * FROM R3 JOIN R2 USING(A) WHERE R3.C > 0 and R2.C >= 5", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopIndexPlanNode);
        assertTrue(((NestLoopIndexPlanNode) n).getPredicate() == null);
        indexScan = (IndexScanPlanNode)n.getInlinePlanNode(PlanNodeType.INDEXSCAN);
        assertTrue(indexScan.getLookupType().equals(IndexLookupType.EQ));
        assertTrue(indexScan.getEndExpression().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        seqScan = (SeqScanPlanNode) n.getChild(0);
        assertTrue(seqScan instanceof SeqScanPlanNode);
        assertTrue(((SeqScanPlanNode)seqScan).getPredicate().getExpressionType().equals(ExpressionType.COMPARE_GREATERTHANOREQUALTO));

        pn = compile("select * FROM R3 JOIN R2 ON R3.A = R2.A JOIN R1 ON R2.A = R1.A WHERE R3.C > 0 and R2.C >= 5", 0, false, null);
        n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopPlanNode);
        p = ((NestLoopPlanNode) n).getPredicate();
        assertTrue(p.getExpressionType() == ExpressionType.COMPARE_EQUAL);
        ExpressionType t = p.getLeft().getExpressionType();
        assertTrue(p.getLeft().getExpressionType() == ExpressionType.VALUE_TUPLE);
        assertTrue(p.getRight().getExpressionType() == ExpressionType.VALUE_TUPLE);
        seqScan = n.getChild(0);
        assertTrue(seqScan instanceof SeqScanPlanNode);
        n = n.getChild(1);
        assertTrue(n instanceof NestLoopIndexPlanNode);
        NestLoopIndexPlanNode nlij = (NestLoopIndexPlanNode) n;
        indexScan = (IndexScanPlanNode)nlij.getInlinePlanNode(PlanNodeType.INDEXSCAN);
        assertTrue(indexScan.getLookupType().equals(IndexLookupType.EQ));
        assertTrue(indexScan.getEndExpression().getExpressionType() == ExpressionType.COMPARE_EQUAL);
        seqScan = (SeqScanPlanNode) nlij.getChild(0);
        assertTrue(seqScan instanceof SeqScanPlanNode);
        assertTrue(((SeqScanPlanNode)seqScan).getPredicate().getExpressionType().equals(ExpressionType.COMPARE_GREATERTHANOREQUALTO));

    }

    public void testIndexInnerJoin() {
        AbstractPlanNode pn = compile("select * FROM R3 JOIN R1 ON R1.C = R3.A", 0, false, null);
        AbstractPlanNode n = pn.getChild(0).getChild(0);
        assertTrue(n instanceof NestLoopIndexPlanNode);
        assertTrue(n.getChild(0) instanceof SeqScanPlanNode);
        assertTrue(n.getInlinePlanNode(PlanNodeType.INDEXSCAN) != null);
    }

   public void testMultiColumnJoin() {
       // Test multi column condition on non index columns
       AbstractPlanNode pn = compile("select A, C FROM R2 JOIN R1 USING(A, C)", 0, false, null);
       AbstractPlanNode n = pn.getChild(0).getChild(0);
       assertTrue(n instanceof NestLoopPlanNode);
       NestLoopPlanNode nlj = (NestLoopPlanNode) n;
       AbstractExpression pred = nlj.getPredicate();
       assertTrue(pred != null);
       assertTrue(pred.getExpressionType() == ExpressionType.CONJUNCTION_AND);

       pn = compile("select R1.A, R2.A FROM R2 JOIN R1 on R1.A = R2.A and R1.C = R2.C", 0, false, null);
       n = pn.getChild(0).getChild(0);
       assertTrue(n instanceof NestLoopPlanNode);
       nlj = (NestLoopPlanNode) n;
       pred = nlj.getPredicate();
       assertTrue(pred != null);
       assertTrue(pred.getExpressionType() == ExpressionType.CONJUNCTION_AND);

      // Test multi column condition on index columns
       pn = compile("select A FROM R2 JOIN R3 USING(A)", 0, false, null);
       n = pn.getChild(0).getChild(0);
       assertTrue(n instanceof NestLoopIndexPlanNode);
       NestLoopIndexPlanNode nlij = (NestLoopIndexPlanNode) n;
       ((IndexScanPlanNode) nlij.getInlinePlanNode(PlanNodeType.INDEXSCAN)).getLookupType().equals(IndexLookupType.GT);

       pn = compile("select R3.A, R2.A FROM R2 JOIN R3 ON R3.A = R2.A", 0, false, null);
       n = pn.getChild(0).getChild(0);
       assertTrue(n instanceof NestLoopIndexPlanNode);
       nlij = (NestLoopIndexPlanNode) n;
       pred = ((IndexScanPlanNode) nlij.getInlinePlanNode(PlanNodeType.INDEXSCAN)).getPredicate();
       ((IndexScanPlanNode) nlij.getInlinePlanNode(PlanNodeType.INDEXSCAN)).getLookupType().equals(IndexLookupType.GT);

       pn = compile("select A, C FROM R3 JOIN R2 USING(A, C)", 0, false, null);
       n = pn.getChild(0).getChild(0);
       assertTrue(n instanceof NestLoopIndexPlanNode);
       nlij = (NestLoopIndexPlanNode) n;
       pred = ((IndexScanPlanNode) nlij.getInlinePlanNode(PlanNodeType.INDEXSCAN)).getPredicate();
       assertTrue(pred != null);
       assertTrue(pred.getExpressionType() == ExpressionType.COMPARE_EQUAL);

       pn = compile("select R3.A, R2.A FROM R3 JOIN R2 ON R3.A = R2.A AND R3.C = R2.C", 0, false, null);
       n = pn.getChild(0).getChild(0);
       assertTrue(n instanceof NestLoopIndexPlanNode);
       nlij = (NestLoopIndexPlanNode) n;
       pred = ((IndexScanPlanNode) nlij.getInlinePlanNode(PlanNodeType.INDEXSCAN)).getPredicate();
       assertTrue(pred != null);
       assertTrue(pred.getExpressionType() == ExpressionType.COMPARE_EQUAL);
       }

   public void testDistributedJoin() {
       // JOIN replicated and one distributed table
       AbstractPlanNode pn = compile("select * FROM R1 JOIN P2 ON R1.C = P2.A", 0, false, null);
       AbstractPlanNode n = pn.getChild(0).getChild(0);
       assertTrue(n instanceof NestLoopIndexPlanNode);

       // Join multiple distributed tables on the partitioned column
       pn = compile("select * FROM P1 JOIN P2 USING(A)", 0, false, null);
       n = pn.getChild(0).getChild(0);
       assertTrue(n instanceof NestLoopIndexPlanNode);

       // Two Distributed tables join on non-partitioned column
       try {
           List<AbstractPlanNode> pnl = aide.compile("select * FROM P1 JOIN P2 ON P1.C = P2.A", 0, false, null);
       } catch (NullPointerException ex) {
           System.out.println(ex.getMessage());
           fail();
       }
   }

   public void testNonSupportedJoin() {
       // JOIN with parentheses (HSQL limitation)
       try {
           List<AbstractPlanNode> pn = aide.compile("select R2.C FROM (R1 JOIN R2 ON R1.C = R2.C) JOIN R3 ON R1.C = R3.C", 0, false, null);
           fail();
       } catch (PlanningErrorException ex) {
           assertTrue("user lacks privilege or object not found: R1.C".equalsIgnoreCase(ex.getMessage()));
       }
       // JOIN with join hierarchy (HSQL limitation)
       try {
           List<AbstractPlanNode> pn = aide.compile("select * FROM R1 JOIN R2 JOIN R3 ON R1.C = R2.C ON R1.C = R3.C", 0, false, null);
           fail();
       } catch (PlanningErrorException ex) {
           assertTrue(ex.getMessage().contains("unexpected token"));
       }
       // FUUL JOIN. Temporary restriction
       try {
           List<AbstractPlanNode> pn = aide.compile("select R1.C FROM R1 FULL JOIN R2 ON R1.C = R2.C", 0, false, null);
           fail();
       } catch (PlanningErrorException ex) {
           assertTrue("VoltDB does not yet support full outer joins".equalsIgnoreCase(ex.getMessage()));
       }
       try {
           List<AbstractPlanNode> pn = aide.compile("select R1.C FROM R1 FULL OUTER JOIN R2 ON R1.C = R2.C", 0, false, null);
           fail();
       } catch (PlanningErrorException ex) {
           assertTrue("VoltDB does not yet support full outer joins".equalsIgnoreCase(ex.getMessage()));
       }
       // OUTER JOIN with more then two tables. Temporary restriction
       try {
           List<AbstractPlanNode> pn = aide.compile("select R1.C FROM R1 LEFT OUTER JOIN R2 ON R1.C = R2.C RIGHT JOIN R3 ON R3.C = R1.C", 0, false, null);
           fail();
       } catch (PlanningErrorException ex) {
           assertTrue("VoltDB does not yet support outer joins with more than two tables involved".equalsIgnoreCase(ex.getMessage()));
       }
       try {
           List<AbstractPlanNode> pn = aide.compile("select R1.C FROM R1 LEFT JOIN R2 ON R1.C = R2.C, R3 WHERE R3.C = R1.C", 0, false, null);
           fail();
       } catch (PlanningErrorException ex) {
           assertTrue("VoltDB does not yet support outer joins with more than two tables involved".equalsIgnoreCase(ex.getMessage()));
       }

       // Self JOIN . Temporary restriction
       try {
           List<AbstractPlanNode> pn = aide.compile("select R1.C FROM R1 LEFT OUTER JOIN R2 ON R1.C = R2.C RIGHT JOIN R2 ON R2.C = R1.C", 0, false, null);
           fail();
       } catch (PlanningErrorException ex) {
           assertTrue("VoltDB does not yet support self joins, consider using views instead".equalsIgnoreCase(ex.getMessage()));
       }
   }

    @Override
    protected void setUp() throws Exception {
        aide = new PlannerTestAideDeCamp(TestJoinOrder.class.getResource("testplans-join-ddl.sql"),
                                         "testplansjoin");

    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        aide.tearDown();
    }
}
