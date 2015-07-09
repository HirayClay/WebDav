package com.vae.wuyunxing.webdav.mobile.main;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.vae.wuyunxing.webdav.library.FileBrowserFactory;
import com.vae.wuyunxing.webdav.library.FileCategory;
import com.vae.wuyunxing.webdav.library.FileExplorer;
import com.vae.wuyunxing.webdav.library.FileInfo;
import com.vae.wuyunxing.webdav.library.exception.DirectoryAlreadyExistsException;
import com.vae.wuyunxing.webdav.library.filter.FileFilter;
import com.vae.wuyunxing.webdav.library.imp.jackrabbit.JackrabbitPath;
import com.vae.wuyunxing.webdav.library.sort.FileSorter;
import com.vae.wuyunxing.webdav.library.util.FileUtil;
import com.vae.wuyunxing.webdav.library.util.PathUtil;
import com.vae.wuyunxing.webdav.mobile.MobileBaseActivity;
import com.vae.wuyunxing.webdav.mobile.R;
import com.vae.wuyunxing.webdav.mobile.main.message.BackParentEvent;
import com.vae.wuyunxing.webdav.mobile.main.message.CreateFileEvent;
import com.vae.wuyunxing.webdav.mobile.main.message.DirChangedEvent;
import com.vae.wuyunxing.webdav.mobile.main.message.EditCheckAllEvent;
import com.vae.wuyunxing.webdav.mobile.main.message.EditSelectionEvent;
import com.vae.wuyunxing.webdav.mobile.main.message.EnterEditModeEvent;
import com.vae.wuyunxing.webdav.mobile.main.message.ExitEditModeEvent;
import com.vae.wuyunxing.webdav.mobile.main.message.FilterFileEvent;
import com.vae.wuyunxing.webdav.mobile.main.message.PlayFileEvent;
import com.vae.wuyunxing.webdav.mobile.main.message.SortFileEvent;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import bolts.Continuation;
import bolts.Task;
import butterknife.ButterKnife;
import butterknife.InjectView;

import de.greenrobot.event.EventBus;

import butterknife.OnItemClick;
import butterknife.OnItemLongClick;
import in.srain.cube.views.ptr.PtrDefaultHandler;
import in.srain.cube.views.ptr.PtrFrameLayout;

public class RemoteFileListFragment extends Fragment {

	@InjectView(R.id.drive_browser_ptr_frame_list)
	PtrFrameLayout mPtrFrameList;

	@InjectView(R.id.drive_browser_file_list)
	ListView mListView;

	@InjectView(R.id.drive_browser_empty_hint)
	FrameLayout mEmptyHint;

	@InjectView(R.id.drive_browser_list_hint)
	TextView mListHint;

	/**
	 * Explorer
	 */
	private FileExplorer   mFileExplorer   = null;
	/** current directory file list */
	private List<FileInfo> mCurDirFileList = null;
	/** display file list (current directory file list sort) */
	private List<FileInfo> mDispFileList   = null;

	/**
	 * state
	 */
	private boolean    mIsRefreshing = false;
	private boolean    mIsInEditMode = false;
	/** filter type */
	private int        mFilterType   = FilterFileEvent.FILTER_TYPE_ALL;
	/** sorter type */
	private FileSorter mSorter       = FileSorter.FILE_NAME_ASCENDING;

	/** Selections */
	private final Set<Integer> mSelections = new HashSet<Integer>();

	private Context mContext;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_drive_browser_file_list, container, false);
		ButterKnife.inject(this, view);
		mContext = getActivity();

		EventBus.getDefault().register(this);

		return view;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		ButterKnife.reset(this);
		EventBus.getDefault().unregister(this);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		initPtrFrameLayout(mPtrFrameList);
		initListView(mListView);
		getRemoteFileList();
	}

	private void showEmptyHint() {
		mPtrFrameList.setVisibility(View.GONE);
		mEmptyHint.setVisibility(View.VISIBLE);
		mListHint.setText(R.string.empty_folder_hint);
	}

	private void clearEmptyHint() {
		mPtrFrameList.setVisibility(View.VISIBLE);
		mEmptyHint.setVisibility(View.GONE);
	}

	private void initListView(ListView listView) {
		listView.setAdapter(new FileListAdapter(LayoutInflater.from(getActivity())));
	}

	private void updateFileListView(List<FileInfo> fileList) {
		((MobileBaseActivity) getActivity()).dismissWaitingDialog();
		if (isRefreshing()) {
			refreshComplete();
		}
		if (fileList == null || fileList.isEmpty()) {
			showEmptyHint();
		} else {
			clearEmptyHint();
			((FileListAdapter) mListView.getAdapter()).setFileList(fileList);
		}
	}

	/** refresh state **/
	private void refreshBegin() {
		setRefreshing(true);
		mPtrFrameList.setEnabled(false);
	}

	private void setRefreshing(boolean refreshing) {
		mIsRefreshing = refreshing;
	}

	private boolean isRefreshing() {
		return mIsRefreshing;
	}

	private void refreshComplete() {
		setRefreshing(false);
		mPtrFrameList.setEnabled(true);
		mPtrFrameList.refreshComplete();
	}
	/****************/

	private void initPtrFrameLayout(PtrFrameLayout ptrFrame) {
		ptrFrame.setPtrHandler(new PtrDefaultHandler() {
			@Override
			public void onRefreshBegin(PtrFrameLayout ptrFrameLayout) {
				refreshBegin();
				updateCurrentFileList();
			}
		});
	}

	/**
	 * get remote file list from WebDAV server
	 */
	private void updateCurrentFileList() {
		getRemoteFileList();
	}

	private void getRemoteFileList() {
		Task.call(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				if (!isRefreshing()) {
					((MobileBaseActivity) getActivity()).showWaitingDialog();
				}
				mCurDirFileList = null;
				mDispFileList = null;
				return null;
			}
		}, Task.UI_THREAD_EXECUTOR).continueWith(new Continuation<Void, Boolean>() {
			@Override
			public Boolean then(Task<Void> task) throws Exception {
				String password = "admin";
				if (mFileExplorer == null) {
					JackrabbitPath path = getJackrabbitPath(password);
					mFileExplorer = FileBrowserFactory.createJackrabbitFileExplorer(path, mContext);
				}
				return true;
			}
		}, Task.BACKGROUND_EXECUTOR).continueWith(new Continuation<Boolean, Void>() {
			@Override
			public Void then(Task<Boolean> task) throws Exception {
				if (task.isCompleted() && task.getResult()) {
					getAndDisplayFileList(".");
				} else {
					/** exception */
					updateFileListView(mDispFileList);
					mFileExplorer = null;
				}
				return null;
			}
		}, Task.UI_THREAD_EXECUTOR);
	}

	/**
	 * get the remote file list and display in ListView
	 * @param path
	 */
	private void getAndDisplayFileList(final String path) {
		Task.call(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				if (!isRefreshing()) {
					((MobileBaseActivity) getActivity()).showWaitingDialog();
				}
				mCurDirFileList = null;
				mDispFileList = null;
				return null;
			}
		}, Task.UI_THREAD_EXECUTOR).continueWith(new Continuation<Void, List<FileInfo>>() {
			@Override
			public List<FileInfo> then(Task<Void> task) throws Exception {
				/** cd (goto path)*/
				mFileExplorer.cd(path);
				/** ls (get the file list)*/
				mCurDirFileList = mFileExplorer.ls("-l");
				/** sort and filter */
				return sortAndFilterFiles(mCurDirFileList);
			}
		}, Task.BACKGROUND_EXECUTOR).continueWith(new Continuation<List<FileInfo>, Void>() {
			@Override
			public Void then(Task<List<FileInfo>> task) throws Exception {
				if (task.isCompleted()) {
					try {
						mDispFileList = task.getResult();
						EventBus.getDefault().post(new DirChangedEvent(mFileExplorer.isRoot(), mFileExplorer.pwd(true)));
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				updateFileListView(mDispFileList);
				return null;
			}
		}, Task.UI_THREAD_EXECUTOR);
	}

	/**
	 * Sort and filter the list
	 * @param all
	 * @return
	 */
	private List<FileInfo> sortAndFilterFiles(final List<FileInfo> all) {
		return sortFiles(filterFiles(all, mFilterType), mSorter);
	}

	/**
	 * filter
	 * @param all
	 * @param filterType
	 * @return
	 */
	private List<FileInfo> filterFiles(List<FileInfo> all, int filterType) {
		FileFilter filter;
		switch (filterType) {
			case FilterFileEvent.FILTER_TYPE_DOC:
				filter = FileCategory.DOCUMENT.getFilter();
				break;
			case FilterFileEvent.FILTER_TYPE_MUSIC:
				filter = FileCategory.AUDIO.getFilter();
				break;
			case FilterFileEvent.FILTER_TYPE_VIDEO:
				filter = FileCategory.VIDEO.getFilter();
				break;
			case FilterFileEvent.FILTER_TYPE_PHOTO:
				filter = FileCategory.IMAGE.getFilter();
				break;
			case FilterFileEvent.FILTER_TYPE_BT:
				filter = FileCategory.BIT_TORRENT.getFilter();
				break;
			case FilterFileEvent.FILTER_TYPE_APP:
				filter = FileCategory.APPLICATION.getFilter();
				break;
			case FilterFileEvent.FILTER_TYPE_ALL:
			default:
				filter = FileCategory.OTHERS.getFilter();
				break;
		}
		return FileUtil.filter(all, filter);
	}

	/**
	 * sorte
	 * @param list
	 * @param sorter
	 * @return
	 */
	private List<FileInfo> sortFiles(final List<FileInfo> list, final FileSorter sorter) {
		final Comparator<FileInfo> comparator = sorter.getSorter();
		Collections.sort(list, comparator);
		return list;
	}

	/**
	 * get jackrabbit path
	 * @param password
	 * @return
	 */
	private JackrabbitPath getJackrabbitPath(String password) {
		String domain = "192.168.31.153";
		String sambaUser = "root";
		String currentUser = "hardy";
		String userStoragePath = "Home";
		String path = PathUtil.appendPath(true, "Screenshots");
		return new JackrabbitPath(domain, path, sambaUser, password);
	}

	private void postSelectionEvent(int selection, int total) {
		EventBus.getDefault().post(new EditSelectionEvent(selection, total));
	}

	void exitEditMode() {
		mIsInEditMode = false;

		mSelections.clear();
		((FileListAdapter) mListView.getAdapter()).clearEditMode();
	}

	private FileSorter updateSorter(FileSorter oldSorter, int sortType) {
		FileSorter newSorter = null;
		switch (sortType) {
			case MainActivity.SORT_TYPE_FILE_NAME:
				if (oldSorter == FileSorter.FILE_NAME_ASCENDING) {
					newSorter = FileSorter.FILE_NAME_DESCENDING;
				} else {
					newSorter = FileSorter.FILE_NAME_ASCENDING;
				}
				break;
			case MainActivity.SORT_TYPE_FILE_SIZE:
				if (oldSorter == FileSorter.FILE_SIZE_ASCENDING) {
					newSorter = FileSorter.FILE_SIZE_DESCENDING;
				} else {
					newSorter = FileSorter.FILE_SIZE_ASCENDING;
				}
				break;
			case MainActivity.SORT_TYPE_DATE:
				if (oldSorter == FileSorter.FILE_DATE_ASCENDING) {
					newSorter = FileSorter.FILE_DATE_DESCENDING;
				} else {
					newSorter = FileSorter.FILE_DATE_ASCENDING;
				}
				break;
			case MainActivity.SORT_TYPE_SUFFIX:
				if (oldSorter == FileSorter.FILE_SUFFIX_ASCENDING) {
					newSorter = FileSorter.FILE_SUFFIX_DESCENDING;
				} else {
					newSorter = FileSorter.FILE_SUFFIX_ASCENDING;
				}
				break;
		}
		return newSorter;
	}

	/*** view operation  **/
	@OnItemClick(R.id.drive_browser_file_list)
	void fileClick(int position) {
		if (mIsInEditMode) {
			FileListAdapter adapter = (FileListAdapter) mListView.getAdapter();
			int hash = ((FileInfo) adapter.getItem(position)).getName().hashCode();
			if (mSelections.contains(hash)) {
				mSelections.remove(hash);
			} else {
				mSelections.add(hash);
			}
			adapter.notifyDataSetChanged();

			postSelectionEvent(mSelections.size(), mCurDirFileList.size());
		} else {
			FileInfo file = (FileInfo) mListView.getAdapter().getItem(position);
			if (file.isDir()) {
				getAndDisplayFileList(file.getName());
			} else {
				EventBus.getDefault().post(new PlayFileEvent(file.getUri()));
			}
		}
	}


	@OnItemLongClick(R.id.drive_browser_file_list)
	boolean enterEditMode(int position) {
		mIsInEditMode = true;

		FileInfo file = (FileInfo) mListView.getAdapter().getItem(position);
		mSelections.add(file.getName().hashCode());
		((FileListAdapter) mListView.getAdapter()).setEditMode(mSelections);

		EventBus.getDefault().post(new EnterEditModeEvent());

		return true;
	}

	/** EventBus event **/
	public void onEventMainThread(BackParentEvent event) {
		if (mIsInEditMode) {
			EventBus.getDefault().post(new ExitEditModeEvent());
		} else if (mFileExplorer == null || mFileExplorer.isRoot()) {
			getActivity().onBackPressed();
		} else {
			getAndDisplayFileList("..");
		}
	}

	public void onEventMainThread(ExitEditModeEvent event) {
		exitEditMode();
	}

	public void onEventMainThread(EditCheckAllEvent event) {
		if (mIsInEditMode) {
			if (event.mIsCheckAll) {
				for (FileInfo file : mCurDirFileList) {
					mSelections.add(file.getName().hashCode());
				}
			} else {
				mSelections.clear();
			}
			((FileListAdapter) mListView.getAdapter()).notifyDataSetChanged();

			postSelectionEvent(mSelections.size(), mCurDirFileList.size());
		}
	}

	public void onEventMainThread(FilterFileEvent event) {
		if (!event.mIsLocalFileFilterEvent) {
			mFilterType = event.mCategory;
			Task.callInBackground(new Callable<List<FileInfo>>() {
				@Override
				public List<FileInfo> call() throws Exception {
					return sortAndFilterFiles(mCurDirFileList);
				}
			}).onSuccess(new Continuation<List<FileInfo>, Void>() {
				@Override
				public Void then(Task<List<FileInfo>> task) throws Exception {
					mDispFileList = task.getResult();
					updateFileListView(mDispFileList);
					return null;
				}
			}, Task.UI_THREAD_EXECUTOR);
		}
	}

	public void onEventMainThread(SortFileEvent event) {
		mSorter = updateSorter(mSorter, event.mSortType);
		Task.callInBackground(new Callable<List<FileInfo>>() {
			@Override
			public List<FileInfo> call() throws Exception {
				return sortFiles(((FileListAdapter) mListView.getAdapter()).getFileList(), mSorter);
			}
		}).onSuccess(new Continuation<List<FileInfo>, Void>() {
			@Override
			public Void then(Task<List<FileInfo>> task) throws Exception {
				mDispFileList = task.getResult();
				updateFileListView(mDispFileList);
				return null;
			}
		}, Task.UI_THREAD_EXECUTOR);
	}

	public void onEventMainThread(CreateFileEvent event) {
		final String filename = event.mFilename;
		Task.callInBackground(new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return mFileExplorer != null && mFileExplorer.mkdir(filename);
			}
		}).continueWith(new Continuation<Boolean, Void>() {
			@SuppressWarnings("ThrowableResultOfMethodCallIgnored")
			@Override
			public Void then(Task<Boolean> task) throws Exception {
				if (task.isFaulted()) {
					if (task.getError() instanceof DirectoryAlreadyExistsException) {
						((MobileBaseActivity) getActivity()).toasts(getString(R.string.directory_already_exists, filename));
					}
				} else {
					if (task.getResult()) {
						getAndDisplayFileList(".");
					} else {
						((MobileBaseActivity) getActivity()).toasts(getString(R.string.new_folder_fail));
					}
				}

				return null;
			}
		}, Task.UI_THREAD_EXECUTOR);
	}

}
