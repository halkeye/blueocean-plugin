import { action, computed, observable } from 'mobx';
import { logging } from '@jenkins-cd/blueocean-core-js';

import { KaraokeApi } from '../../index';

const logger = logging.logger('io.jenkins.blueocean.dashboard.karaoke.Pager');

/**
 * The pager fetches pages of data from the BlueOcean api. It fetches pages of data, then
 * inserts them into the [@link BunkerService], and stores the href from the data.
 *
 * MobX computes a data field from the href backed by the backend cache. This allows for SSE events
 * to be propagated to the pager.
 *
 * @export
 * @class Pager
 */
export class LogPager {
    /**
     * pager is fetching data. log and detail
     * @type {boolean}
     */
    @observable pending = false;
    /**
     * Will be set in an error occurs.
     * @type {object|null}
     */
    @observable error = null;

    /**
     * Mobx computed value that creates an object. If either the  bunker changes,
     * or the href change, this is recalculated and will trigger a react reaction.
     *
     * If item does not exist in bunker, then we just ignore it.
     * @readonly
     * @type {object}
     */
    @computed
    get log() {
        return this.bunker.getItem(this.augmenter.step.logUrl);
    }
    /**
     * Creates an instance of Pager and fetches the first page.
     *
     * @param {BunkerService} bunker - Data store
     * @param {object} pipeline Pipeline that this pager belongs to.
     * @param {string} branch the name of the branch we are requesting
     * @param {string} run Run that this pager belongs to.
     */
    constructor(bunker, augmenter) {
        this.bunker = bunker;
        this.augmenter = augmenter;
    }

    /**
     * Fetches the detail from the backend and set the data
     *
     * @returns {Promise}
     */
    @action
    fetchLog({ start, followAlong }) {
        clearTimeout(this.timeout);
        // while fetching we are pending
        this.pending = true;
        // log is text and not json, further it does not has _link in the response
        const logData = {
            _links: {
                self: {
                    href: this.augmenter.step.logUrl,
                },
            },
        };
        // get api data and further process it
        return KaraokeApi.getGeneralLog(this.augmenter.step.logUrl, { start })
            .then(response => {
                const { newStart, hasMore } = response;
                logger.warn({ newStart, hasMore });
                logData.hasMore = start === '0' ? false : hasMore;
                logData.newStart = newStart;
                return response.text();
            })
            .then(action('Process pager data', text => {
                if (text && text.trim) {
                    logData.data = text.trim().split('\n');
                }
                // Store item in bunker.
                this.bunker.setItem(logData);
                logger.debug('saved data', this.augmenter.generalLogUrl, logData.newStart, followAlong);
                this.pending = false;
                if (Number(logData.newStart) > 0 && followAlong) {
                    // kill current  timeout if any
                    clearTimeout(this.timeout);
                    // we need to get more input from the log stream
                    this.timeout = setTimeout(() => {
                        const props = { start: logData.newStart, followAlong };
                        logger.warn(props);
                        this.followLog(logData);
                    }, 1000);
                }
            })).catch(err => {
                logger.error('Error fetching page', err);
                action('set error', () => { this.error = err; });
            });
    }

    /**
     * Fetches the detail from the backend and set the data
     *
     * @returns {Promise}
     */
    @action
    followLog(logDataOrg) {
        clearTimeout(this.timeout);
        const logData = { ...logDataOrg };
        // update link to trigger adding a new part of the log to the partial object
        // logData._links.self.href = `${this.generalLogUrl}?start=${logData.newStart}`;
        // while fetching we are pending
        this.pending = true;
        logger.warn('changed ref', logData._links.self.href);
        return KaraokeApi.getGeneralLog(this.augmenter.step.logUrl, { start: logData.newStart })
            .then(action('Process pager data following 1', response => {
                const { newStart, hasMore } = response;
                logger.warn({ newStart, hasMore });
                logData.newStart = response.newStart;
                return response.text();
            }))
            .then(action('Process pager data following 2', text => {
                if (text && text.trim) {
                    logData.data = logData.data.concat(text.trim().split('\n'));
                }
                // Store item in bunker.
                this.bunker.setItem(logData);
                logger.debug('saved data', this.augmenter.generalLogUrl, logData.newStart);
                this.pending = false;
                if (logData.newStart !== null) {
                    // kill current  timeout if any
                    // we need to get mpre input from the log stream
                    this.timeout = setTimeout(() => {
                        this.followLog(logData);
                    }, 1000);
                }
            })).catch(err => {
                logger.error('Error fetching page', err);
                action('set error', () => { this.error = err; });
            });
    }


    clear() {
        clearTimeout(this.timeout);
    }
}
