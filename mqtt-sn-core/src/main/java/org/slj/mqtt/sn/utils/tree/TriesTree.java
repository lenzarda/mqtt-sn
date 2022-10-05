/*
 * Copyright (c) 2021 Simon Johnson <simon622 AT gmail DOT com>
 *
 * Find me on GitHub:
 * https://github.com/simon622
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.slj.mqtt.sn.utils.tree;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Simple TriesTree implementation designed to add members at each level of the tree and normalise the storage
 */
public class TriesTree<T> {

    private static final int DEFAULT_MAX_PATH_SIZE = 1024 * 10;
    private static final int DEFAULT_MAX_PATH_SEGMENTS = 1024;
    private static final int DEFAULT_MAX_MEMBERS_AT_LEVEL = 1024 * 10;

    private final String pathSplitStr;
    private final String pathSplitRegex;
    private Pattern pattern;
    private Set<String> wildcards = new HashSet<>();
    private Set<String> wildpaths = new HashSet<>();
    private boolean selfPruningTree;
    private final TrieNode<T> root;
    private long maxPathSize = DEFAULT_MAX_PATH_SIZE;
    private long maxPathSegments = DEFAULT_MAX_PATH_SEGMENTS;
    private long maxMembersAtLevel = DEFAULT_MAX_MEMBERS_AT_LEVEL;

    /**
     * Construct your tree taking the immutable configuration for the rest of the life of the tree.
     *
     * @param pathSplitRegex - the regex around which to split your tree path, for example '/my/tree/file/system' could be
     *                       split around '/' which would yield a tree with 4 levels
     * @param pathSplitStr - the string to use to reconstitute the tree when (NB: this is often the same as the @param pathSplitRegex,
     *                     but sometimes differs when regex control characters are used).
     * @param selfPruningTree - when removing members, when a leaf is determined to be empty subsequent to the removal operation, should the
     *                        tree at that level be pruned (where it is the last level of the tree)
     */
    public TriesTree(final String pathSplitRegex, final String pathSplitStr, final boolean selfPruningTree){
        this.pathSplitStr = pathSplitStr;
        this.pathSplitRegex = pathSplitRegex;
        this.pattern = Pattern.compile(pathSplitRegex);
        this.selfPruningTree = selfPruningTree;
        this.root = new TrieNode<T>( null, null);
    }

    public long getMaxPathSize() {
        return maxPathSize;
    }

    public void setMaxPathSize(long maxPathSize) {
        this.maxPathSize = maxPathSize;
    }

    public long getMaxPathSegments() {
        return maxPathSegments;
    }

    public void setMaxPathSegments(long maxPathSegments) {
        this.maxPathSegments = maxPathSegments;
    }

    public long getMaxMembersAtLevel() {
        return maxMembersAtLevel;
    }

    public void setMaxMembersAtLevel(long maxMembersAtLevel) {
        this.maxMembersAtLevel = maxMembersAtLevel;
    }

    public void addWildcard(String wildcard){
        if(wildcard == null) throw new NullPointerException("wild card cannot be <null>");
        wildcards.add(wildcard);
    }

    public void addWildpath(String wildpath){
        if(wildpaths == null) throw new NullPointerException("wild path cannot be <null>");
        wildpaths.add(wildpath);
    }

    public void addPath(final String path, final T... members) throws TriesTreeLimitExceededException {

        if(path.length() > maxPathSize)
            throw new TriesTreeLimitExceededException("cannot add paths lengths exceeding the configured max '"+maxPathSize+"'");

        String[] segments = split(path);

        if(segments.length > maxPathSegments)
            throw new TriesTreeLimitExceededException("cannot add paths exceeding the configured max segments '"+maxPathSegments+"'");

        if(members != null && members.length > maxMembersAtLevel)
            throw new TriesTreeLimitExceededException("cannot add paths with the number of members exceeding max '"+maxMembersAtLevel+"'");

        TrieNode<T> node = root;
        for (int i=0; i<segments.length; i++){
            if(i == segments.length - 1){
                node = node.addChild(segments[i], members);
            } else {
                node = node.addChild(segments[i]);
            }
        }
    }

    public void removeMemberFromPath(final String path, T member){
        String[] segments = split(path);
        TrieNode<T> node = root;
        for (int i=0; i<segments.length; i++){
            node = node.getChild(segments[i]);
        }
        if(node != null){
            //if the leaf now contains no members, cut the leaf off the tree
            if(node.removeMember(member) && selfPruningTree &&
                    node.getMembers().isEmpty() && !node.hasChildren()){
                node.getParent().removeChild(node);
            }
        }
    }

    public Set<T> search(final String path){
        String[] segments = split(path);
        return searchTree(root, segments);
    }

    public static void visitChildren(TriesTree.TrieNode node, Visitor visitor) {
        if (node != null) {
            Set<String> children = node.getChildPaths();
            Iterator<String> itr = children.iterator();
            while (itr.hasNext()) {
                String path = itr.next();
                TriesTree.TrieNode child = node.getChild(path);
                if (child == null) {
                    throw new RuntimeException("encountered invalid tree state");
                } else {
                    visitChildren(child, visitor);
                    visitor.visit(child);
                }
            }
        }
    }

    protected Set<T> searchTree(TrieNode<T> node, String[] segments){

        if(node == null) throw new NullPointerException("cannot search a null node");
        Set<T> merged = new HashSet<>();

        for (int i=0; i < segments.length; i++){
            if(!wildcards.isEmpty()){
                for (String wildcard: wildcards) {
                    TrieNode<T> wild = node.getChild(wildcard);
                    if(wild != null){
                        Set<T> members = wild.getMembers();
                        if(members != null && !members.isEmpty()){
                            merged.addAll(members);
                        }
                    }
                }
            }

            if(!wildpaths.isEmpty()){
                for (String wildpath: wildpaths) {
                    TrieNode<T> wild = node.getChild(wildpath);
                    if(wild != null){
                        String[] remainingSegments =
                                Arrays.copyOfRange(segments, i, segments.length - 1);
                        //recurse point
                        Set<T> wildMembers = searchTree(wild, remainingSegments);
                        if(wildMembers != null && !wildMembers.isEmpty()){
                            merged.addAll(wildMembers);
                        }
                    }
                }
            }
            node = node.getChild(segments[i]);
            if(node == null) break;
        }

        if(node != null){
            Set<T> members = node.getMembers();
            if(!members.isEmpty()){
                merged.addAll(members);
            }
        }
        return merged;
    }

    public String toTree(String lineSep){
        StringBuilder sb = new StringBuilder();
        visitChildren(root, n -> {
            if(!n.hasChildren()){
                if(sb.length() > 0){
                    sb.append(lineSep);
                }
                sb.append(n.toPath(true));
            }
        });
        return sb.toString();
    }

    /**
     * Return a set of paths which are either entirely distinct or have been made distinct with memberships.
     * @param considerMembership - true if you wish to include sub paths with memberships as distinct from
     *                           the child paths thereafter.
     *                           For example, '/this/path/here' and '/this/path' with membership included at '/this/path' would be included once without
     *                           considering membership just returning '/this/path/here' or 2 dintinct entries if memberships were considered
     * @return the distinct paths
     */
    public Set<String> getDistinctPaths(boolean considerMembership){
        Set<String> paths = new HashSet<>();
        visitChildren(root, n -> {
            //-- either its a leaf node or a node with children but also members
            if(!n.hasChildren() || (considerMembership && n.hasMembers())){
                paths.add(n.toPath(true));
            }
        });
        return paths;
    }

    public int getBranchCount(){
        return root.getChildPaths().size();
    }

    protected String[] split(final String path){
        return pattern.split(path);
    }

    class TrieNode<T> {
        private Map<String, TrieNode<T>> children = new HashMap<>();
        private String pathSegment;
        private volatile Set<T> members;
        private TrieNode parent;
        private final boolean isRoot;
        protected TrieNode(final TrieNode parent, final String pathSegment){
            this.parent = parent;
            this.pathSegment = pathSegment;
            this.isRoot = parent == null;
            if(!isRoot){
                if(pathSegment == null)
                    throw new IllegalArgumentException("unable to add null element child to " + parent.pathSegment);
            }
        }

        public TrieNode addChild(final String pathSegment, final T... membersIn) throws TriesTreeLimitExceededException {
            if(pathSegment == null) throw new IllegalArgumentException("unable to mount <null> leaf to tree");
            TrieNode child;
            if(!children.containsKey(pathSegment)){
                synchronized (this){
                    if(!children.containsKey(pathSegment)){
                        child = new TrieNode<>(this, pathSegment);
                        children.put(pathSegment, child);
                    } else {
                        child = getChild(pathSegment);
                    }
                }
            } else {
                child = getChild(pathSegment);
            }
            child.addMembers(membersIn);
            return child;
        }

        public TrieNode getChild(final String path){
            return children.get(path);
        }

        public boolean hasChild(String path){
            return children.containsKey(path);
        }

        public boolean hasChildren(){
            return !children.isEmpty();
        }

        public boolean hasMembers(){
            return members != null && !members.isEmpty();
        }

        public TrieNode getParent(){
            return parent;
        }

        public boolean isRoot(){
            return isRoot;
        }

        public void removeChild(TrieNode node){
            if(children.containsKey(node.pathSegment)){
                synchronized (children){
                    TrieNode removed = children.remove(node.pathSegment);
                    if(removed != node){
                        throw new RuntimeException("node removal inconsistency");
                    }
                }
                node.parent = null;
            }
        }

        public void addMembers(T... membersIn) throws TriesTreeLimitExceededException {
            if(members == null && membersIn != null) {
                synchronized (this) {
                    if(members == null)
                        members = new HashSet<>();
                }
            }

            if(membersIn != null){
                if(members.size() + membersIn.length > TriesTree.this.getMaxMembersAtLevel()){
                    throw new TriesTreeLimitExceededException("member limit exceeded at level");
                }
                members.addAll(Arrays.asList(membersIn));
            }
        }

        public boolean removeMember(T member){
            synchronized (this){
                return members.remove(member);
            }
        }

        public Set<T> getMembers(){
            synchronized (this){
                return Collections.unmodifiableSet(members);
            }
        }

        public Set<String> getChildPaths(){
            synchronized (children){
                return Collections.unmodifiableSet(children.keySet());
            }
        }

        @Override
        public String toString() {
            return "TrieNode{" +
                    "pathSegment='" + pathSegment + '\'' +
                    ", parent=" + parent +
                    ", isRoot=" + isRoot +
                    '}';
        }


        public String toPath(boolean climb){
            if(climb){
                List<String> l = new ArrayList<>();
                TrieNode<T> leaf = this;
                while(leaf != null){
                    if(leaf.isRoot()) break;
                    l.add(leaf.pathSegment);
                    leaf = leaf.getParent();
                }
                StringBuilder sb = new StringBuilder();
                for (int i = l.size(); i-- > 0; ) {
                    sb.append(l.get(i));
                }
                return sb.toString();
            } else {
                return pathSegment;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TrieNode<?> trieNode = (TrieNode<?>) o;
            if (!pathSegment.equals(trieNode.pathSegment)) return false;
            return parent != null ? parent.equals(trieNode.parent) : trieNode.parent == null;
        }

        @Override
        public int hashCode() {
            int result = pathSegment.hashCode();
            result = 31 * result + (parent != null ? parent.hashCode() : 0);
            return result;
        }
    }
}

interface Visitor {

    void visit(TriesTree.TrieNode node);
}
