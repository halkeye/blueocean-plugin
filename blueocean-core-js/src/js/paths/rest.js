import { JenkinsEncode } from '../utils/jenkins-encode';
/**
 * This object defines rest paths
 */
export const RestPaths = {
    _convertSlashes(pipeline) {
        return pipeline.replace(/\//g, '/pipelines/');
    },

    apiRoot() {
        return '/blue/rest';
    },

    pipelines(organizationName, searchText) {
        const organization = organizationName ? `;organization:${encodeURIComponent(organizationName)}` : '';
        let searchTextQuery = '';

        if (searchText) {
            searchTextQuery = ('*' + searchText + '*').replace(/\//g, '*/*').replace('**', '*');
        }

        return `${this.apiRoot()}/search/?q=type:pipeline${organization};pipeline:${encodeURIComponent(
            searchTextQuery
        )};excludedFromFlattening:jenkins.branch.MultiBranchProject,hudson.matrix.MatrixProject&filter=no-folders`;
    },

    runs(organization, pipeline, branch) {
        const branchStr = branch ? `?branch=${branch}` : '';
        return `${this.apiRoot()}/organizations/${encodeURIComponent(organization)}/pipelines/${pipeline}/runs/${branchStr}`;
    },

    run({ organization, pipeline, branch, runId }) {
        if (branch) {
            return `${this.pipeline(organization, pipeline)}branches/${encodeURIComponent(JenkinsEncode.encode(branch))}/runs/${runId}/`;
        }

        return `${this.pipeline(organization, pipeline)}runs/${runId}/`;
    },

    disable(organization, pipeline) {
        return `${this.apiRoot()}/organizations/${encodeURIComponent(organization)}/pipelines/${this._convertSlashes(pipeline)}/disable`;
    },

    enable(organization, pipeline) {
        return `${this.apiRoot()}/organizations/${encodeURIComponent(organization)}/pipelines/${this._convertSlashes(pipeline)}/enable`;
    },

    pipeline(organization, pipeline) {
        return `${this.apiRoot()}/organizations/${encodeURIComponent(organization)}/pipelines/${this._convertSlashes(pipeline)}/`;
    },

    branches(organization, pipeline) {
        return `${this.apiRoot()}/organizations/${encodeURIComponent(organization)}/pipelines/${pipeline}/branches/?filter=origin`;
    },

    pullRequests(organization, pipeline) {
        return `${this.apiRoot()}/organizations/${encodeURIComponent(organization)}/pipelines/${pipeline}/branches/?filter=pull-requests`;
    },
};
