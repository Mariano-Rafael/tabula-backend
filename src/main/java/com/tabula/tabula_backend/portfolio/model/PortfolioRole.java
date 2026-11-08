package com.tabula.tabula_backend.portfolio.model;

public enum PortfolioRole {
    OWNER, EDITOR, OPERATOR, VIEWER;

    public boolean canEditProjects() {
        return this == OWNER || this == EDITOR;
    }
}
