import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LogSearchService, LogEntry } from '../log-search.service';

@Component({
  selector: 'app-log-search',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './log-search.component.html',
  styleUrl: './log-search.component.scss'
})
export class LogSearchComponent implements OnInit, OnDestroy {
  searchQuery: string = '';
  selectedFile: string = 'all';
  maxLines: number = 1000;
  fromDate: string = '';
  toDate: string = '';
  
  logEntries: LogEntry[] = [];
  availableFiles: string[] = [];
  loading: boolean = false;
  error: string | null = null;
  searchPerformed: boolean = false;
  
  // Auto-refresh settings
  autoRefreshEnabled: boolean = false;
  refreshInterval: number = 5; // seconds
  private refreshTimer: any = null;

  constructor(private logSearchService: LogSearchService) {}

  ngOnInit() {
    this.loadAvailableFiles();
    this.loadRecentLogs();
  }
  
  ngOnDestroy() {
    this.stopAutoRefresh();
  }

  loadAvailableFiles() {
    this.logSearchService.getAvailableLogFiles().subscribe({
      next: (response) => {
        this.availableFiles = response.files;
      },
      error: (err) => {
        console.error('Error loading log files:', err);
      }
    });
  }

  loadRecentLogs() {
    this.loading = true;
    this.error = null;
    this.logSearchService.getRecentLogs(undefined, this.maxLines).subscribe({
      next: (response) => {
        this.logEntries = response.entries;
        this.loading = false;
        this.searchPerformed = false;
        if (response.error) {
          this.error = response.error;
        }
      },
      error: (err) => {
        this.error = 'Error loading recent logs: ' + (err.message || 'Unknown error');
        this.loading = false;
      }
    });
  }
  
  toggleAutoRefresh() {
    if (this.autoRefreshEnabled) {
      this.startAutoRefresh();
    } else {
      this.stopAutoRefresh();
    }
  }
  
  onAutoRefreshChange() {
    this.toggleAutoRefresh();
  }
  
  startAutoRefresh() {
    this.stopAutoRefresh(); // Clear any existing timer
    if (this.autoRefreshEnabled && this.refreshInterval > 0) {
      this.refreshTimer = setInterval(() => {
        if (this.searchPerformed && this.searchQuery.trim()) {
          // If there's an active search, refresh the search
          this.searchLogs();
        } else {
          // Otherwise refresh recent logs
          this.loadRecentLogs();
        }
      }, this.refreshInterval * 1000);
    }
  }
  
  stopAutoRefresh() {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }
  
  onRefreshIntervalChange() {
    if (this.autoRefreshEnabled) {
      this.startAutoRefresh();
    }
  }

  searchLogs() {
    if (!this.searchQuery.trim() && this.selectedFile === 'all' && !this.fromDate && !this.toDate) {
      // If no search criteria, just show recent logs
      this.loadRecentLogs();
      return;
    }

    this.loading = true;
    this.error = null;
    this.searchPerformed = true;

    const file = this.selectedFile === 'all' ? undefined : this.selectedFile;
    const fromDate = this.fromDate || undefined;
    const toDate = this.toDate || undefined;
    const query = this.searchQuery.trim() || undefined;

    this.logSearchService.searchLogs(query, file, this.maxLines, fromDate, toDate).subscribe({
      next: (response) => {
        this.logEntries = response.entries;
        this.loading = false;
        if (response.error) {
          this.error = response.error;
        }
      },
      error: (err) => {
        this.error = 'Error searching logs: ' + (err.message || 'Unknown error');
        this.loading = false;
      }
    });
  }

  clearSearch() {
    this.searchQuery = '';
    this.selectedFile = 'all';
    this.fromDate = '';
    this.toDate = '';
    this.maxLines = 1000;
    this.error = null;
    this.searchPerformed = false;
    this.loadRecentLogs();
  }

  highlightText(text: string, query: string): string {
    if (!query || !query.trim()) {
      return text;
    }
    const regex = new RegExp(`(${this.escapeRegex(query)})`, 'gi');
    return text.replace(regex, '<mark>$1</mark>');
  }

  private escapeRegex(str: string): string {
    return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
}

