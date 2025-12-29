import { Routes } from '@angular/router';
import { HomeComponent } from './home/home.component';
import { LogSearchComponent } from './log-search/log-search.component';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'logs', component: LogSearchComponent },
  { path: '**', redirectTo: '' }
];

