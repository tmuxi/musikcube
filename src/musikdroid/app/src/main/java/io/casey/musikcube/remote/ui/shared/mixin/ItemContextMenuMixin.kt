package io.casey.musikcube.remote.ui.shared.mixin

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import io.casey.musikcube.remote.Application
import io.casey.musikcube.remote.R
import io.casey.musikcube.remote.framework.MixinBase
import io.casey.musikcube.remote.injection.DaggerViewComponent
import io.casey.musikcube.remote.service.playback.PlaybackServiceFactory
import io.casey.musikcube.remote.service.websocket.Messages
import io.casey.musikcube.remote.service.websocket.model.IAlbum
import io.casey.musikcube.remote.service.websocket.model.ICategoryValue
import io.casey.musikcube.remote.service.websocket.model.IDataProvider
import io.casey.musikcube.remote.service.websocket.model.ITrack
import io.casey.musikcube.remote.ui.albums.activity.AlbumBrowseActivity
import io.casey.musikcube.remote.ui.category.activity.CategoryBrowseActivity
import io.casey.musikcube.remote.ui.category.constant.NavigationType
import io.casey.musikcube.remote.ui.shared.extension.hideKeyboard
import io.casey.musikcube.remote.ui.shared.extension.showErrorSnackbar
import io.casey.musikcube.remote.ui.shared.extension.showKeyboard
import io.casey.musikcube.remote.ui.shared.extension.showSnackbar
import io.casey.musikcube.remote.ui.shared.fragment.BaseDialogFragment
import io.casey.musikcube.remote.ui.tracks.activity.TrackListActivity
import io.reactivex.Observable
import io.reactivex.rxkotlin.subscribeBy
import javax.inject.Inject

class ItemContextMenuMixin(private val activity: AppCompatActivity,
                           internal val listener: EventListener? = null): MixinBase() {
    private enum class TrackType { Normal, Playlist }

    @Inject lateinit var provider: IDataProvider

    open class EventListener {
        open fun onPlaylistDeleted(id: Long) { }
        open fun onPlaylistCreated(id: Long) { }
        open fun onPlaylistUpdated(id: Long) { }
    }

    private var pendingCode = -1
    private var completion: ((Long) -> Unit)? = null

    init {
        DaggerViewComponent.builder()
            .appComponent(Application.appComponent)
            .build().inject(this)
    }

    override fun onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        ConfirmDeletePlaylistDialog.rebind(activity, this)
        EnterPlaylistNameDialog.rebind(activity, this)
        ConfirmRemoveFromPlaylistDialog.rebind(activity, this)
    }

    override fun onResume() {
        super.onResume()
        provider.attach()
    }

    override fun onPause() {
        super.onPause()
        provider.detach()
    }

    override fun onDestroy() {
        super.onDestroy()
        provider.destroy()
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (pendingCode == request) {
            if (result == Activity.RESULT_OK && data != null) {
                val playlistId = data.getLongExtra(CategoryBrowseActivity.EXTRA_ID, -1L)
                if (playlistId != -1L) {
                    completion?.invoke(playlistId)
                }
            }
            pendingCode = -1
            completion = null
        }

        super.onActivityResult(request, result, data)
    }

    fun createPlaylist() =
        EnterPlaylistNameDialog.showForCreate(activity, this)

    fun createPlaylist(playlistName: String) {
        provider.createPlaylist(playlistName).subscribeBy(
            onNext = { id ->
                if (id > 0L) {
                    listener?.onPlaylistCreated(id)
                    showSuccess(activity.getString(R.string.playlist_created, playlistName))
                }
                else {
                    showError(activity.getString(R.string.playlist_not_created, playlistName))
                }
            },
            onError = {
                showError(activity.getString(R.string.playlist_not_created, playlistName))
            })
    }

    fun renamePlaylist(newName: String, id: Long) {
        provider.renamePlaylist(id, newName).subscribeBy(
            onNext = { success ->
                if (success) {
                    listener?.onPlaylistUpdated(id)
                    showSuccess(activity.getString(R.string.playlist_renamed, newName))
                }
                else {
                    showError(activity.getString(R.string.playlist_not_renamed, newName))
                }
            },
            onError = {
                showError(activity.getString(R.string.playlist_not_renamed, newName))
            })
    }

    fun addToPlaylist(track: ITrack) =
        addToPlaylist(listOf(track))

    fun addToPlaylist(tracks: List<ITrack>) {
        showPlaylistChooser { id ->
            addWithErrorHandler(id, provider.appendToPlaylist(id, tracks))
        }
    }

    fun addToPlaylist(categoryType: String, categoryId: Long) {
        showPlaylistChooser { id ->
            addWithErrorHandler(id, provider.appendToPlaylist(id, categoryType, categoryId))
        }
    }

    fun addToPlaylist(category: ICategoryValue) {
        showPlaylistChooser { id ->
            addWithErrorHandler(id, provider.appendToPlaylist(id, category))
        }
    }

    private fun addWithErrorHandler(playlistId: Long, observable: Observable<Boolean>) {
        val error = R.string.playlist_edit_add_error

        observable.subscribeBy(
            onNext = { success ->
                if (success) {
                    listener?.onPlaylistUpdated(playlistId)
                    showSuccess(R.string.playlist_edit_add_success)
                }
                else {
                    showError(error)
                }
            },
            onError = { showError(error) })
    }

    private fun showPlaylistChooser(callback: (Long) -> Unit) {
        completion = callback
        pendingCode = REQUEST_ADD_TO_PLAYLIST

        val intent = CategoryBrowseActivity.getStartIntent(
            activity,
            Messages.Category.PLAYLISTS,
            NavigationType.Select,
            activity.getString(R.string.playlist_edit_pick_playlist))

        activity.startActivityForResult(intent, pendingCode)
    }

    fun showForTrack(track: ITrack, anchorView: View) {
        showForTrack(track, -1, -1, anchorView, TrackType.Normal)
    }

    fun showForPlaylistTrack(track: ITrack, position: Int, playlistId: Long, anchorView: View) {
        showForTrack(track, position, playlistId, anchorView, TrackType.Playlist)
    }

    private fun showForTrack(track: ITrack, position: Int, categoryId: Long, anchorView: View, type: TrackType) {
        val popup = PopupMenu(activity, anchorView)
        popup.inflate(R.menu.track_item_context_menu)

        if (type != TrackType.Playlist) {
            popup.menu.removeItem(R.id.menu_remove_from_playlist)
        }

        popup.setOnMenuItemClickListener { item ->
            val intent: Intent? = when (item.itemId) {
                R.id.menu_add_to_playlist -> {
                    addToPlaylist(track)
                    null
                }
                R.id.menu_remove_from_playlist -> {
                    ConfirmRemoveFromPlaylistDialog.show(
                        activity, this, categoryId, position, track)
                    null
                }
                R.id.menu_show_artist_albums -> {
                    AlbumBrowseActivity.getStartIntent(
                        activity, Messages.Category.ARTIST, track.artistId, track.artist)
                }
                R.id.menu_show_artist_tracks -> {
                    TrackListActivity.getStartIntent(
                        activity, Messages.Category.ARTIST, track.artistId, track.artist)
                }
                else -> null
            }

            if (intent != null) {
                activity.startActivity(intent)
            }

            true
        }

        popup.show()
    }

    fun showForPlaylist(playlistName: String, playlistId: Long, anchorView: View) {
        val popup = PopupMenu(activity, anchorView)
        popup.inflate(R.menu.playlist_item_context_menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_playlist_delete -> {
                    ConfirmDeletePlaylistDialog.show(activity, this, playlistName, playlistId)
                }
                R.id.menu_playlist_rename -> {
                    EnterPlaylistNameDialog.showForRename(activity, this, playlistName, playlistId)
                }
                R.id.menu_playlist_play -> {
                    val playback = PlaybackServiceFactory.instance(Application.instance!!)
                    playback.play(Messages.Category.PLAYLISTS, playlistId)
                }
            }
            true
        }

        popup.show()
    }

    fun showForCategory(value: ICategoryValue, anchorView: View) {
        if (value.type == Messages.Category.PLAYLISTS) {
            showForPlaylist(value.value, value.id, anchorView)
        }
        else {
            val menuId = when (value.type) {
                Messages.Category.ARTIST -> R.menu.artist_item_context_menu
                Messages.Category.ALBUM_ARTIST -> R.menu.artist_item_context_menu
                Messages.Category.ALBUM -> R.menu.album_item_context_menu
                Messages.Category.GENRE -> R.menu.genre_item_context_menu
                else -> -1
            }

            if (menuId != -1) {
                val popup = PopupMenu(activity, anchorView)
                popup.inflate(menuId)

                popup.setOnMenuItemClickListener { item ->
                    val intent: Intent? = when (item.itemId) {
                        R.id.menu_add_to_playlist -> {
                            addToPlaylist(value)
                            null
                        }
                        R.id.menu_show_artist_albums,
                        R.id.menu_show_genre_albums -> {
                            if (value is IAlbum) {
                                AlbumBrowseActivity.getStartIntent(
                                    activity,
                                    Messages.Category.ALBUM_ARTIST,
                                    value.albumArtistId,
                                    value.albumArtist)
                            }
                            else {
                                AlbumBrowseActivity.getStartIntent(
                                    activity, value.type, value.id, value.value)
                            }
                        }
                        R.id.menu_show_artist_tracks,
                        R.id.menu_show_genre_tracks -> {
                            if (value is IAlbum) {
                                TrackListActivity.getStartIntent(
                                    activity,
                                    Messages.Category.ALBUM_ARTIST,
                                    value.albumArtistId,
                                    value.albumArtist)
                            }
                            else {
                                TrackListActivity.getStartIntent(
                                    activity, value.type, value.id, value.value)
                            }
                        }
                        R.id.menu_show_artist_genres -> {
                            CategoryBrowseActivity.getStartIntent(
                                activity, Messages.Category.GENRE, value.type, value.id, value.value)
                        }
                        R.id.menu_show_genre_artists -> {
                            CategoryBrowseActivity.getStartIntent(
                                activity, Messages.Category.ARTIST, value.type, value.id, value.value)
                        }
                        else -> null
                    }

                    if (intent != null) {
                        activity.startActivity(intent)
                    }

                    true
                }

                popup.show()
            }
        }
    }

    private fun deletePlaylistConfirmed(playlistId: Long, playlistName: String) {
        if (playlistId != -1L) {
            provider.deletePlaylist(playlistId).subscribeBy(
                onNext = { success ->
                    if (success) {
                        listener?.onPlaylistDeleted(playlistId)
                        showSuccess(activity.getString(R.string.playlist_deleted, playlistName))
                    }
                    else {
                        showSuccess(activity.getString(R.string.playlist_not_deleted, playlistName))
                    }
                },
                onError = {
                    showSuccess(activity.getString(R.string.playlist_not_deleted, playlistName))
                })
        }
    }

    private fun removeFromPlaylistConfirmed(playlistId: Long, externalId: String, position: Int) {
        provider.removeTracksFromPlaylist(playlistId, listOf(externalId), listOf(position)).subscribeBy(
            onNext = { success ->
                listener?.onPlaylistUpdated(playlistId)
            },
            onError = {

            }
        )
    }

    private fun showSuccess(stringId: Int) =
        showSuccess(activity.getString(stringId))

    private fun showSuccess(message: String) =
        showSnackbar(activity.findViewById(android.R.id.content), message)

    private fun showError(message: Int) =
        showError(activity.getString(message))

    private fun showError(message: String) =
        showErrorSnackbar(activity.findViewById(android.R.id.content), message)

    class ConfirmRemoveFromPlaylistDialog : BaseDialogFragment() {
        private lateinit var mixin: ItemContextMenuMixin

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val playlistId = arguments.getLong(EXTRA_PLAYLIST_ID, -1)
            val trackTitle = arguments.getString(EXTRA_TRACK_TITLE, "")
            val trackExternalId = arguments.getString(EXTRA_TRACK_EXTERNAL_ID, "")
            val trackPosition = arguments.getInt(EXTRA_TRACK_POSITION, -1)

            val dlg = AlertDialog.Builder(activity)
                .setTitle(R.string.playlist_confirm_delete_title)
                .setMessage(getString(R.string.playlist_confirm_delete_message, trackTitle))
                .setNegativeButton(R.string.button_no, null)
                .setPositiveButton(R.string.button_yes, { _: DialogInterface, _: Int ->
                    mixin.removeFromPlaylistConfirmed(playlistId, trackExternalId, trackPosition)
                })
                .create()

            dlg.setCancelable(false)
            return dlg
        }

        companion object {
            val TAG = "confirm_delete_playlist_dialog"
            private val EXTRA_PLAYLIST_ID = "extra_playlist_id"
            private val EXTRA_TRACK_TITLE = "extra_track_title"
            private val EXTRA_TRACK_EXTERNAL_ID = "extra_track_external_id"
            private val EXTRA_TRACK_POSITION = "extra_track_position"

            fun rebind(activity: AppCompatActivity, mixin: ItemContextMenuMixin) {
                find<ConfirmRemoveFromPlaylistDialog>(activity, TAG)?.mixin = mixin
            }

            fun show(activity: AppCompatActivity, mixin: ItemContextMenuMixin, playlistId: Long, position: Int, track: ITrack) {
                dismiss(activity, TAG)
                val args = Bundle()
                args.putLong(EXTRA_PLAYLIST_ID, playlistId)
                args.putString(EXTRA_TRACK_TITLE, track.title)
                args.putString(EXTRA_TRACK_EXTERNAL_ID, track.externalId)
                args.putInt(EXTRA_TRACK_POSITION, position)
                val result = ConfirmRemoveFromPlaylistDialog()
                result.arguments = args
                result.mixin = mixin
                result.show(activity.supportFragmentManager, TAG)
            }
        }
    }

    class ConfirmDeletePlaylistDialog : BaseDialogFragment() {
        private lateinit var mixin: ItemContextMenuMixin

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val playlistName = arguments.getString(EXTRA_PLAYLIST_NAME, "")

            val dlg = AlertDialog.Builder(activity)
                .setTitle(R.string.playlist_confirm_delete_title)
                .setMessage(getString(R.string.playlist_confirm_delete_message, playlistName))
                .setNegativeButton(R.string.button_no, null)
                .setPositiveButton(R.string.button_yes, { _: DialogInterface, _: Int ->
                    val playlistId = arguments.getLong(EXTRA_PLAYLIST_ID, -1)
                    mixin.deletePlaylistConfirmed(playlistId, playlistName)
                })
                .create()

            dlg.setCancelable(false)
            return dlg
        }

        companion object {
            val TAG = "confirm_delete_playlist_dialog"
            private val EXTRA_PLAYLIST_ID = "extra_playlist_id"
            private val EXTRA_PLAYLIST_NAME = "extra_playlist_name"

            fun rebind(activity: AppCompatActivity, mixin: ItemContextMenuMixin) {
                find<ConfirmDeletePlaylistDialog>(activity, TAG)?.mixin = mixin
            }

            fun show(activity: AppCompatActivity, mixin: ItemContextMenuMixin, name: String, id: Long) {
                dismiss(activity, TAG)

                val args = Bundle()
                args.putString(EXTRA_PLAYLIST_NAME, name)
                args.putLong(EXTRA_PLAYLIST_ID, id)
                val result = ConfirmDeletePlaylistDialog()
                result.arguments = args
                result.mixin = mixin

                result.show(activity.supportFragmentManager, TAG)
            }
        }
    }

    class EnterPlaylistNameDialog: BaseDialogFragment() {
        enum class Action { Create, Rename }
        private lateinit var mixin: ItemContextMenuMixin

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val editText = EditText(activity)
            val action = arguments.getSerializable(EXTRA_ACTION)
            val name = arguments.getString(EXTRA_NAME, "")
            val id = arguments.getLong(EXTRA_ID, -1)

            val buttonId = if (action == Action.Rename)
                R.string.button_rename else R.string.button_create

            if (name.isNotBlank()) {
                editText.setText(name)
                editText.selectAll()
            }

            val dlg = AlertDialog.Builder(activity)
                .setTitle(R.string.playlist_name_title)
                    .setNegativeButton(R.string.button_cancel, null)
                    .setPositiveButton(buttonId, { _: DialogInterface, _: Int ->
                    val playlistName = editText.text.toString()
                    if (playlistName.isNotBlank()) {
                        when (action) {
                            Action.Create -> mixin.createPlaylist(playlistName)
                            Action.Rename -> mixin.renamePlaylist(playlistName, id)
                        }
                    }
                    else {
                        mixin.showError(R.string.playlist_name_error_empty)
                    }
                })
                .create()

            val paddingX = activity.resources.getDimensionPixelSize(R.dimen.edit_text_dialog_padding_x)
            val paddingY = activity.resources.getDimensionPixelSize(R.dimen.edit_text_dialog_padding_y)
            dlg.setView(editText, paddingX, paddingY, paddingX, paddingY)

            dlg.setCancelable(false)
            return dlg
        }

        override fun onResume() {
            super.onResume()
            showKeyboard()
        }

        override fun onPause() {
            super.onPause()
            hideKeyboard()
        }

        companion object {
            val TAG = "EnterPlaylistNameDialog"
            private val EXTRA_ACTION = "extra_action"
            private val EXTRA_NAME = "extra_name"
            private val EXTRA_ID = "extra_id"

            fun rebind(activity: AppCompatActivity, mixin: ItemContextMenuMixin) {
                find<EnterPlaylistNameDialog>(activity, TAG)?.mixin = mixin
            }

            fun showForCreate(activity: AppCompatActivity, mixin: ItemContextMenuMixin) {
                dismiss(activity, TAG)
                val bundle = Bundle()
                bundle.putSerializable(EXTRA_ACTION, Action.Create)
                show(activity, mixin, bundle)
            }

            fun showForRename(activity: AppCompatActivity, mixin: ItemContextMenuMixin, name: String, id: Long) {
                dismiss(activity, TAG)
                val bundle = Bundle()
                bundle.putSerializable(EXTRA_ACTION, Action.Rename)
                bundle.putString(EXTRA_NAME, name)
                bundle.putLong(EXTRA_ID, id)
                show(activity, mixin, bundle)
            }

            private fun show(activity: AppCompatActivity, mixin: ItemContextMenuMixin, bundle: Bundle) {
                dismiss(activity, TAG)
                val dialog = EnterPlaylistNameDialog()
                dialog.arguments = bundle
                dialog.mixin = mixin
                dialog.show(activity.supportFragmentManager, TAG)
            }
        }
    }

    companion object {
        private val REQUEST_ADD_TO_PLAYLIST = 128
    }
}