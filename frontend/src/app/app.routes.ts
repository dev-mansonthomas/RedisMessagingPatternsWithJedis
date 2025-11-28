import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: '/dlq',
    pathMatch: 'full'
  },
  {
    path: 'dlq',
    loadComponent: () => import('./components/dlq/dlq.component').then(m => m.DlqComponent)
  },
  {
    path: 'pubsub',
    loadComponent: () => import('./components/pubsub/pubsub.component').then(m => m.PubsubComponent)
  },
  {
    path: 'request-reply',
    loadComponent: () => import('./components/request-reply/request-reply.component').then(m => m.RequestReplyComponent)
  },
  {
    path: '**',
    redirectTo: '/dlq'
  }
];
